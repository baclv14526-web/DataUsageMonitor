# ── Giữ lại toàn bộ class của app (tránh crash do ProGuard đổi tên) ──
-keep class com.datamonitor.app.** { *; }

# ── Kotlin coroutines ──────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── AndroidX / Support Library ─────────────────────────────────────────
-keep class androidx.core.app.** { *; }
-dontwarn androidx.**

# ── Giữ lại các annotation quan trọng ─────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable  # Giúp đọc stack trace khi crash

# ── Bỏ qua cảnh báo không ảnh hưởng ──────────────────────────────────
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
