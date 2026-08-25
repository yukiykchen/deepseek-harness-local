#import "DshSseModule.h"

#import <OpenKuiklyIOSRender/NSObject+KR.h>

@interface DshSseConnection : NSObject <NSURLSessionDataDelegate, NSURLSessionWebSocketDelegate>

@property (nonatomic, copy) KuiklyRenderCallback callback;
@property (nonatomic, copy) dispatch_block_t onFinished;
@property (nonatomic, strong) NSMutableData *buffer;
@property (nonatomic, strong, nullable) NSURLSession *session;
@property (nonatomic, strong, nullable) NSURLSessionDataTask *task;
@property (nonatomic, strong, nullable) NSURLSessionWebSocketTask *webSocketTask;
@property (atomic, assign) BOOL closed;
@property (atomic, assign) BOOL usingWebSocket;
@property (atomic, assign) BOOL finished;

- (instancetype)initWithURL:(NSURL *)url
                      token:(NSString *)token
                   callback:(KuiklyRenderCallback)callback
                 onFinished:(dispatch_block_t)onFinished;
- (void)start;
- (void)close;

@end

@implementation DshSseConnection {
    NSURL *_url;
    NSString *_token;
}

- (instancetype)initWithURL:(NSURL *)url
                      token:(NSString *)token
                   callback:(KuiklyRenderCallback)callback
                 onFinished:(dispatch_block_t)onFinished {
    self = [super init];
    if (self) {
        _url = url;
        _token = [token copy];
        _callback = [callback copy];
        _onFinished = [onFinished copy];
        _buffer = [NSMutableData data];
    }
    return self;
}

- (void)start {
    NSURLSessionConfiguration *configuration = [NSURLSessionConfiguration defaultSessionConfiguration];
    configuration.timeoutIntervalForRequest = 10;
    self.session = [NSURLSession sessionWithConfiguration:configuration delegate:self delegateQueue:nil];
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:_url];
    request.HTTPMethod = @"GET";
    [request setValue:@"text/event-stream" forHTTPHeaderField:@"Accept"];
    [request setValue:@"no-cache" forHTTPHeaderField:@"Cache-Control"];
    if (_token.length > 0) {
        [request setValue:[NSString stringWithFormat:@"Bearer %@", _token]
       forHTTPHeaderField:@"Authorization"];
    }
    self.task = [self.session dataTaskWithRequest:request];
    [self.task resume];
}

- (void)close {
    self.closed = YES;
    [self.task cancel];
    [self.webSocketTask cancelWithCloseCode:NSURLSessionWebSocketCloseCodeNormalClosure reason:nil];
    [self.session invalidateAndCancel];
    self.task = nil;
    self.webSocketTask = nil;
    self.session = nil;
    [self finish];
}

- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask
didReceiveResponse:(NSURLResponse *)response
 completionHandler:(void (^)(NSURLSessionResponseDisposition disposition))completionHandler {
    NSInteger statusCode = [(NSHTTPURLResponse *)response statusCode];
    if (statusCode == 426) {
        self.usingWebSocket = YES;
        completionHandler(NSURLSessionResponseCancel);
        [self startWebSocket];
        return;
    }
    if (statusCode < 200 || statusCode > 299) {
        self.closed = YES;
        [self emit:@{ @"kind": @"ERROR", @"message": [NSString stringWithFormat:@"events.mux failed with HTTP %ld", (long)statusCode] }];
        completionHandler(NSURLSessionResponseCancel);
        return;
    }
    [self emit:@{ @"kind": @"OPEN" }];
    completionHandler(NSURLSessionResponseAllow);
}

- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveData:(NSData *)data {
    [self.buffer appendData:data];
    [self consumeEvents];
}

- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task
didCompleteWithError:(NSError *)error {
    if (task == self.task && self.usingWebSocket) {
        self.task = nil;
        return;
    }
    if (!self.closed) {
        if (error) {
            [self emit:@{ @"kind": @"ERROR", @"message": error.localizedDescription ?: @"SSE connection failed" }];
        } else {
            [self emit:@{ @"kind": @"CLOSED" }];
        }
    }
    [self.session finishTasksAndInvalidate];
    self.task = nil;
    self.webSocketTask = nil;
    self.session = nil;
    [self finish];
}

- (void)startWebSocket {
    NSURLComponents *components = [NSURLComponents componentsWithURL:_url resolvingAgainstBaseURL:NO];
    components.scheme = [components.scheme isEqualToString:@"https"] ? @"wss" : @"ws";
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:components.URL];
    if (_token.length > 0) {
        [request setValue:[NSString stringWithFormat:@"Bearer %@", _token]
       forHTTPHeaderField:@"Authorization"];
    }
    self.webSocketTask = [self.session webSocketTaskWithRequest:request];
    [self.webSocketTask resume];
}

- (void)URLSession:(NSURLSession *)session
      webSocketTask:(NSURLSessionWebSocketTask *)webSocketTask
didOpenWithProtocol:(NSString *)protocol {
    if (self.closed) return;
    [self emit:@{ @"kind": @"OPEN" }];
    [self receiveWebSocketMessage];
}

- (void)URLSession:(NSURLSession *)session
      webSocketTask:(NSURLSessionWebSocketTask *)webSocketTask
didCloseWithCode:(NSURLSessionWebSocketCloseCode)closeCode
             reason:(NSData *)reason {
    self.webSocketTask = nil;
    if (!self.closed) [self emit:@{ @"kind": @"CLOSED" }];
    [self.session finishTasksAndInvalidate];
    self.session = nil;
    [self finish];
}

- (void)receiveWebSocketMessage {
    if (self.closed || !self.webSocketTask) return;
    __weak typeof(self) weakSelf = self;
    [self.webSocketTask receiveMessageWithCompletionHandler:^(NSURLSessionWebSocketMessage *message, NSError *error) {
        __strong typeof(weakSelf) self = weakSelf;
        if (!self || self.closed) return;
        if (error) {
            [self emit:@{ @"kind": @"ERROR", @"message": error.localizedDescription ?: @"WebSocket connection failed" }];
            [self.webSocketTask cancelWithCloseCode:NSURLSessionWebSocketCloseCodeGoingAway reason:nil];
            self.webSocketTask = nil;
            [self.session invalidateAndCancel];
            self.session = nil;
            [self finish];
            return;
        }
        if (message.type == NSURLSessionWebSocketMessageTypeString && message.string.length > 0) {
            [self emit:@{ @"kind": @"FRAME", @"data": message.string }];
        }
        [self receiveWebSocketMessage];
    }];
}

- (void)consumeEvents {
    static const unsigned char delimiterBytes[] = { '\n', '\n' };
    NSData *delimiter = [NSData dataWithBytes:delimiterBytes length:sizeof(delimiterBytes)];
    while (self.buffer.length > 0) {
        NSRange range = [self.buffer rangeOfData:delimiter options:0 range:NSMakeRange(0, self.buffer.length)];
        if (range.location == NSNotFound) return;
        NSData *eventData = [self.buffer subdataWithRange:NSMakeRange(0, range.location)];
        [self.buffer replaceBytesInRange:NSMakeRange(0, NSMaxRange(range)) withBytes:NULL length:0];
        NSString *event = [[NSString alloc] initWithData:eventData encoding:NSUTF8StringEncoding];
        if (!event) continue;
        NSMutableArray<NSString *> *dataLines = [NSMutableArray array];
        for (NSString *line in [event componentsSeparatedByString:@"\n"]) {
            if (![line hasPrefix:@"data:"]) continue;
            NSString *value = [line substringFromIndex:5];
            if ([value hasPrefix:@" "]) value = [value substringFromIndex:1];
            [dataLines addObject:value];
        }
        if (dataLines.count > 0) {
            [self emit:@{ @"kind": @"FRAME", @"data": [dataLines componentsJoinedByString:@"\n"] }];
        }
    }
}

- (void)emit:(NSDictionary *)event {
    KuiklyRenderCallback callback = self.callback;
    if (!callback) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        callback(event);
    });
}

- (void)finish {
    @synchronized (self) {
        if (self.finished) return;
        self.finished = YES;
    }
    if (self.onFinished) self.onFinished();
}

@end

@interface DshSseModule ()

@property (nonatomic, strong) NSMutableDictionary<NSString *, DshSseConnection *> *connections;

@end

@implementation DshSseModule

@synthesize hr_rootView;

- (instancetype)init {
    self = [super init];
    if (self) _connections = [NSMutableDictionary dictionary];
    return self;
}

- (void)dealloc {
    for (DshSseConnection *connection in self.connections.allValues) {
        [connection close];
    }
}

- (void)connect:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    NSURL *url = [NSURL URLWithString:params[@"url"] ?: @""];
    if (connectionId.length == 0 || !url || !callback) return;
    [self.connections[connectionId] close];
    __weak typeof(self) weakSelf = self;
    DshSseConnection *connection = [[DshSseConnection alloc]
        initWithURL:url
              token:params[@"token"] ?: @""
           callback:callback
         onFinished:^{
             [weakSelf.connections removeObjectForKey:connectionId];
         }];
    self.connections[connectionId] = connection;
    [connection start];
}

- (void)disconnect:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    NSString *connectionId = params[@"connectionId"];
    [self.connections[connectionId] close];
    [self.connections removeObjectForKey:connectionId];
}

@end
