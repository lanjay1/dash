# ZTune ProGuard / R8 rules
#
# Currently minifyEnabled=false on release, so this file is not yet active.
# Kept as a placeholder so the release build type can reference it without
# failing the build. When minify is enabled in a future phase, add keep rules
# here for:
#   - Hilt generated classes
#   - kotlinx-serialization @Serializable classes
#   - Compose lambdas (usually handled by default rules)
#   - usb-serial-for-android library classes
#   - Reflection-based INI expression evaluator
