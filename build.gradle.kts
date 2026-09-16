// Plugin versions được quản lý trong settings.gradle.kts
// Root build file chỉ cần task clean
tasks.register("clean", Delete::class) {
    // Dùng layout.buildDirectory thay cho buildDir (deprecated từ Gradle 8.x)
    delete(layout.buildDirectory)
}
