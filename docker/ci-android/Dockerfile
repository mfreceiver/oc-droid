# ocdroid Android CI 镜像。
# 在 Unraid 宿主(act_runner 同一 Docker daemon)上构建:
#   docker build -t universal-ci-android:35-jdk21 docker/ci-android/
# 版本须与 gradle/libs.versions.toml + app/build.gradle.kts 对齐:
#   JDK 21 / compileSdk 35 / build-tools 35.0.0。Gradle 由项目 ./gradlew 自带(不在镜像内)。
# act_runner 标签映射: android:docker://universal-ci-android:35-jdk21
FROM eclipse-temurin:21-jdk-jammy

ARG CMDLINE_TOOLS=11076708
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}"

RUN apt-get update \
 && apt-get install -y --no-install-recommends git unzip wget ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /opt/android-sdk/cmdline-tools \
 && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" -O /tmp/cmdline.zip \
 && unzip -q /tmp/cmdline.zip -d /opt/android-sdk/cmdline-tools \
 && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
 && rm /tmp/cmdline.zip \
 && yes | sdkmanager --licenses >/dev/null \
 && sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
 && yes | sdkmanager --licenses >/dev/null

WORKDIR /workspace
