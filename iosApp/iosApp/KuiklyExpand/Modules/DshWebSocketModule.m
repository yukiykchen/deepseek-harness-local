#import "DshWebSocketModule.h"

#import <OpenKuiklyIOSRender/NSObject+KR.h>

@interface DshWebSocketConnection : NSObject <NSURLSessionWebSocketDelegate>
@property (nonatomic, copy) KuiklyRenderCallback callback;
@property (nonatomic, copy) dispatch_block_t onFinished;
@property (nonatomic, strong, nullable) NSURLSession *session;
@property (nonatomic, strong, nullable) NSURLSessionWebSocketTask *webSocketTask;
@property (atomic, assign) BOOL closed;
@property (atomic, assign) BOOL finished;
- (instancetype)initWithURL:(NSURL *)url token:(NSString *)token callback:(KuiklyRenderCallback)callback onFinished:(dispatch_block_t)onFinished;
- (void)start;
- (void)close;
@end

@implementation DshWebSocketConnection {
    NSURL *_url;
    NSString *_token;
}

- (instancetype)initWithURL:(NSURL *)url token:(NSString *)token callback:(KuiklyRenderCallback)callback onFinished:(dispatch_block_t)onFinished {
    self = [super init];
    if (self) {
        _url = url;
        _token = [token copy];
        _callback = [callback copy];
        _onFinished = [onFinished copy];
    }
    return self;
}

- (void)start {
    NSURLComponents *components = [NSURLComponents componentsWithURL:_url resolvingAgainstBaseURL:NO];
    components.scheme = [components.scheme isEqualToString:@"https"] ? @"wss" : @"ws";
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:components.URL];
    if (_token.length > 0) {
        [request setValue:[NSString stringWithFormat:@"Bearer %@", _token] forHTTPHeaderField:@"Authorization"];
    }
    NSURLSessionConfiguration *configuration = [NSURLSessionConfiguration defaultSessionConfiguration];
    configuration.timeoutIntervalForRequest = 10;
    self.session = [NSURLSession sessionWithConfiguration:configuration delegate:self delegateQueue:nil];
    self.webSocketTask = [self.session webSocketTaskWithRequest:request];
    [self.webSocketTask resume];
}

- (void)close {
    self.closed = YES;
    [self.webSocketTask cancelWithCloseCode:NSURLSessionWebSocketCloseCodeNormalClosure reason:nil];
    [self.session invalidateAndCancel];
    self.webSocketTask = nil;
    self.session = nil;
    [self finish];
}

- (void)URLSession:(NSURLSession *)session webSocketTask:(NSURLSessionWebSocketTask *)webSocketTask didOpenWithProtocol:(NSString *)protocol {
    if (self.closed) return;
    [self emit:@{ @"kind": @"OPEN" }];
    [self receiveWebSocketMessage];
}

- (void)URLSession:(NSURLSession *)session webSocketTask:(NSURLSessionWebSocketTask *)webSocketTask didCloseWithCode:(NSURLSessionWebSocketCloseCode)closeCode reason:(NSData *)reason {
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

- (void)emit:(NSDictionary *)event {
    KuiklyRenderCallback callback = self.callback;
    if (!callback) return;
    dispatch_async(dispatch_get_main_queue(), ^{ callback(event); });
}

- (void)finish {
    @synchronized (self) {
        if (self.finished) return;
        self.finished = YES;
    }
    if (self.onFinished) self.onFinished();
}

@end

@interface DshWebSocketModule ()
@property (nonatomic, strong) NSMutableDictionary<NSString *, DshWebSocketConnection *> *connections;
@end

@implementation DshWebSocketModule

@synthesize hr_rootView;

- (instancetype)init {
    self = [super init];
    if (self) _connections = [NSMutableDictionary dictionary];
    return self;
}

- (void)dealloc {
    for (DshWebSocketConnection *connection in self.connections.allValues) [connection close];
}

- (void)connect:(NSDictionary *)args {
    NSDictionary *params = [args[KR_PARAM_KEY] hr_stringToDictionary];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSString *connectionId = params[@"connectionId"];
    NSURL *url = [NSURL URLWithString:params[@"url"] ?: @""];
    if (connectionId.length == 0 || !url || !callback) return;
    [self.connections[connectionId] close];
    __weak typeof(self) weakSelf = self;
    DshWebSocketConnection *connection = [[DshWebSocketConnection alloc]
        initWithURL:url token:params[@"token"] ?: @"" callback:callback onFinished:^{
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
