# source this: toolchain for building AmiPro-APK (shared per-user toolchain from the Filen project)
export JAVA_HOME="$HOME/.local/share/filen-jdk21"
export ANDROID_HOME="$HOME/.local/share/filen-android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$HOME/.claude/.cargo/bin:$PATH"
