# kotlinx-serialization looks up Companion.serializer() by name on @Serializable types.
# Scope is our code: core.protocol plus the SMS and call cursors, which are @Serializable and encoded at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,EnclosingMethod

-if @kotlinx.serialization.Serializable class app.handlive.android.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class app.handlive.android.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class app.handlive.android.** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink Android 1.23.0 ships only META-INF/proguard/protobuf.pro. AeadConfig.register, AndroidKeysetManager
# and the subtle primitives (X25519, Ed25519, XChaCha20-Poly1305) are referenced directly, so no extra keep.

# firebase-messaging 25.1.3 has no consumer rules. gms component discovery instantiates registrars named in manifest meta-data.
-keep class * extends com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# Ktor still names Netty HTTP/3 and QUIC types; those modules are excluded and the server leaves HTTP/3 off.
-dontwarn io.netty.handler.codec.http3.Http3
-dontwarn io.netty.handler.codec.http3.Http3Headers
-dontwarn io.netty.handler.codec.http3.Http3HeadersFrame
-dontwarn io.netty.handler.codec.http3.Http3RequestStreamInboundHandler
-dontwarn io.netty.handler.codec.http3.Http3ServerConnectionHandler
-dontwarn io.netty.handler.codec.quic.EpollQuicUtils
-dontwarn io.netty.handler.codec.quic.QuicChannel
-dontwarn io.netty.handler.codec.quic.QuicChannelOption
-dontwarn io.netty.handler.codec.quic.QuicCodecBuilder
-dontwarn io.netty.handler.codec.quic.QuicCodecDispatcher
-dontwarn io.netty.handler.codec.quic.QuicConnectionIdGenerator
-dontwarn io.netty.handler.codec.quic.QuicServerCodecBuilder
-dontwarn io.netty.handler.codec.quic.QuicSslContext
-dontwarn io.netty.handler.codec.quic.QuicSslContextBuilder
-dontwarn io.netty.handler.codec.quic.QuicStreamChannel
-dontwarn io.netty.handler.codec.quic.QuicTokenHandler
-dontwarn io.netty.handler.codec.quic.SegmentedDatagramPacketAllocator

# Netty's OpenSSL path (tcnative) is not on Android; the server uses the JDK TLS provider.
-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher

# JDK management and JFR APIs that Netty and Ktor probe, absent from the Android runtime.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
-dontwarn jdk.jfr.Category
-dontwarn jdk.jfr.DataAmount
-dontwarn jdk.jfr.Description
-dontwarn jdk.jfr.Enabled
-dontwarn jdk.jfr.Event
-dontwarn jdk.jfr.FlightRecorder
-dontwarn jdk.jfr.Label
-dontwarn jdk.jfr.MemoryAddress
-dontwarn jdk.jfr.Name

# kotlin-reflect is excluded from the server classpath; Ktor only uses it for dev auto-reload.
-dontwarn kotlin.reflect.full.KCallables
-dontwarn kotlin.reflect.full.KClasses
-dontwarn kotlin.reflect.jvm.ReflectJvmMapping

# Netty's ReflectiveChannelFactory calls Class.getConstructor() on these channels.
# -keep, not -keepclassmembers: R8 otherwise inlines the no-arg constructor away.
-keep class io.netty.channel.socket.nio.NioServerSocketChannel {
    public <init>();
}
-keep class io.netty.channel.socket.nio.NioSocketChannel {
    public <init>();
}
-keep class io.netty.channel.socket.nio.NioDatagramChannel {
    public <init>();
}

# Netty calls Class.getDeclaredMethod by name (ConcurrentSkipListIntObjMultimap.acquireFenceFallback)
# and R8 also drops abstract methods that only satisfy a buffer interface. ART then rejects the class.
# Member names stay. Class names can still be obfuscated, and unused classes can still be removed.
# SSL, tcnative, HTTP/3 and QUIC stay out: those members reference classes that are not on Android.
-keepclassmembers class !io.netty.handler.ssl.**, !io.netty.internal.tcnative.**, !io.netty.handler.codec.http3.**, !io.netty.handler.codec.quic.**, io.netty.** {
    *;
}

# -keep, not -keepclassmembers: R8 otherwise merges Log4J2Logger into a concrete class
# whose InternalLogger methods (debug, info, warn, error, trace) stay abstract.
-keep class io.netty.util.internal.logging.** {
    *;
}

# Reactor BlockHound is an optional service file Netty references; the class is not on Android.
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# Optional Netty log backends that are not dependencies.
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
