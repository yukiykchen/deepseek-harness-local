import SwiftUI
import shared

struct ContentView: View {

    var body: some View {
        let databaseDir = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Databases", isDirectory: true)
        try? FileManager.default.createDirectory(at: databaseDir, withIntermediateDirectories: true)
        return KuiklyRenderViewPage(
            pageName: "connection_setup",
            data: ["databaseDir": databaseDir.path]
        ).ignoresSafeArea()
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
