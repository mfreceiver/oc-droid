# universal-ci-android:通用 Android CI 镜像,ocdroid / x-liker / sync-player 共用。
# 在 Unraid 宿主(act_runner 同一 Docker daemon)上构建:
#   docker build -t universal-ci-android:35-jdk21 docker/ci-android/
# 版本须与各项目 gradle/libs.versions.toml + app/build.gradle.kts 对齐:
#   JDK 21 / compileSdk 35 / build-tools 36.0.0(AGP 9.x 默认;3 项目均已升 AGP 9.1)。
#   Gradle 由项目 ./gradlew 自带(不在镜像内)。
# act_runner 标签映射: android:docker://universal-ci-android:35-jdk21
#
# 包含:
#   - eclipse-temurin:21-jdk-jammy(JDK 21 + Ubuntu 22.04,glibc)
#   - Android SDK:platform-tools / platforms;android-35 / build-tools;35.0.0 + 36.0.0
#   - Python 3.10 + requests + yaml + jinja2(发布脚本用)
#   - git / unzip / wget / ca-certificates
#
# 瘦身策略(单 RUN,同层内删除不进 layer):
#   - 预装 build-tools;36.0.0(消除每个 job 在线下载)
#   - 构建后删 cmdline-tools/latest/lib(sdkmanager 实现包,运行时不调用)
#   - strip /usr/share/{doc,man}
# 工作流约定:不直接调 sdkmanager;需要新 SDK 组件时重建镜像。
FROM eclipse-temurin:21-jdk-jammy

ARG CMDLINE_TOOLS=11076708
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}"

# 单 RUN:apt 依赖 + Python C + Android SDK + 后置清理(同层删除不进 layer)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      git unzip wget ca-certificates \
      python3 python3-requests python3-yaml python3-jinja2 \
 && rm -rf /var/lib/apt/lists/* /usr/share/doc/* /usr/share/man/* \
 && mkdir -p /opt/android-sdk/cmdline-tools \
 && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" -O /tmp/cmdline.zip \
 && unzip -q /tmp/cmdline.zip -d /opt/android-sdk/cmdline-tools \
 && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
 && rm /tmp/cmdline.zip \
 && yes | sdkmanager --licenses >/dev/null \
 && sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;36.0.0" \
 && yes | sdkmanager --licenses >/dev/null \
 && rm -rf /opt/android-sdk/cmdline-tools/latest/lib \
          /opt/android-sdk/cmdline-tools/latest/source.properties

WORKDIR /workspace
