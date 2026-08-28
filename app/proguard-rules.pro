-keepattributes Signature,*Annotation*

-keep,allowoptimization class kotlinx.coroutines.BuildersKt {
    public static java.lang.Object runBlocking(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2);
}

# JNA's native dispatcher and reflection resolve classes, fields, and Library
# method names directly. Keep this framework boundary stable while allowing Iroh
# and application classes to remain fully optimized and obfuscated.
-keep class com.sun.jna.** { *; }
-keepclassmembers class computer.iroh.EndpointBuilder {
    public <init>();
}
# The release smoke invokes these shipping APIs from a separately minified test
# split. Prevent R8 from specializing their signatures to app-only continuation
# implementations while still allowing names/classes to be obfuscated.
-keepclassmembers,allowobfuscation class computer.iroh.EndpointBuilder {
    public void applyMinimal();
    public void alpns(java.util.List);
    public java.lang.Object bind(kotlin.coroutines.Continuation);
}
-keepclassmembers,allowobfuscation class computer.iroh.Endpoint {
    public java.lang.Object acceptNext(kotlin.coroutines.Continuation);
    public java.lang.Object connect(computer.iroh.EndpointAddr, byte[], kotlin.coroutines.Continuation);
    public computer.iroh.EndpointAddr addr();
    public computer.iroh.EndpointId id();
    public java.lang.Object shutdown(kotlin.coroutines.Continuation);
}
-keepclassmembers,allowobfuscation class computer.iroh.Incoming {
    public java.lang.Object accept(kotlin.coroutines.Continuation);
}
-keepclassmembers,allowobfuscation class computer.iroh.Accepting {
    public java.lang.Object alpn(kotlin.coroutines.Continuation);
    public java.lang.Object connect(kotlin.coroutines.Continuation);
}
-keepclassmembers,allowobfuscation class computer.iroh.Connection {
    public byte[] alpn();
    public computer.iroh.EndpointId remoteId();
}
-keep @com.sun.jna.Structure$FieldOrder class * { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
    public <init>();
}
-keep interface * extends com.sun.jna.Library { *; }
-keep interface * extends com.sun.jna.Callback { *; }
-keep class * implements com.sun.jna.Callback { *; }
-dontwarn java.awt.**
