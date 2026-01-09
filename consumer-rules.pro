# Desmo SDK ProGuard Rules
# These rules are applied to apps that consume this SDK

# ============================================================================
# Room Persistence Library
# ============================================================================
# Keep Room entities and DAOs
-keep class io.getdesmo.tracesdk.telemetry.persistence.** { *; }

# ============================================================================
# kotlinx.serialization
# ============================================================================
# Keep serialization annotations
-keepattributes *Annotation*, InnerClasses

# Suppress notes about serialization internals
-dontnote kotlinx.serialization.**

# Keep Companion objects for serializers
-keepclassmembers class io.getdesmo.tracesdk.** {
    *** Companion;
}

# Keep serializer functions
-keepclasseswithmembers class io.getdesmo.tracesdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ============================================================================
# SDK Model Classes
# ============================================================================
# Keep all model classes used for JSON serialization
-keep class io.getdesmo.tracesdk.models.** { *; }

# Keep network request/response classes
-keep class io.getdesmo.tracesdk.network.StartSessionRequest { *; }
-keep class io.getdesmo.tracesdk.network.StopSessionRequest { *; }
-keep class io.getdesmo.tracesdk.network.RequestError { *; }
-keep class io.getdesmo.tracesdk.network.RequestError$* { *; }

# Keep telemetry payload classes
-keep class io.getdesmo.tracesdk.telemetry.TelemetrySample { *; }
-keep class io.getdesmo.tracesdk.telemetry.TelemetryRequest { *; }
-keep class io.getdesmo.tracesdk.telemetry.ImuPayload { *; }
-keep class io.getdesmo.tracesdk.telemetry.BarometerPayload { *; }
-keep class io.getdesmo.tracesdk.telemetry.PositionPayload { *; }
-keep class io.getdesmo.tracesdk.telemetry.ContextPayload { *; }
-keep class io.getdesmo.tracesdk.telemetry.SensorAvailability { *; }

# ============================================================================
# API Classes
# ============================================================================
# Keep public API error types
-keep class io.getdesmo.tracesdk.api.DesmoClientError { *; }
-keep class io.getdesmo.tracesdk.api.DesmoClientError$* { *; }
-keep class io.getdesmo.tracesdk.api.DesmoResult { *; }
-keep class io.getdesmo.tracesdk.api.DesmoResult$* { *; }
