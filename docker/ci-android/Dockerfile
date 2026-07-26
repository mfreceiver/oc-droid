# universal-ci-android:通用 Android CI 镜像,ocdroid / x-liker / sync-player 共用。
# 在 Unraid 宿主(act_runner 同一 Docker daemon)上构建:
#   docker build -t universal-ci-android:35-jdk21 docker/ci-android/
# 版本须与各项目 gradle/libs.versions.toml + app/build.gradle.kts 对齐:
#   JDK 21 / compileSdk 35 / build-tools 36.0.0(AGP 9.x 默认;3 项目均已升 AGP 9.1)。
#   Gradle 9.3.1 预装到 /root/.gradle/wrapper/dists/(各项目 ./gradlew 直接命中,跳过下载)。
# act_runner 标签映射: android:docker://universal-ci-android:35-jdk21
#
# 包含:
#   - eclipse-temurin:21-jdk-jammy(JDK 21 + Ubuntu 22.04,glibc)
#   - Android SDK:platform-tools / platforms;android-35 / build-tools;36.0.0
#   - Gradle 9.3.1 dist(wrapper 格式;./gradlew 检查到 .ok marker 跳过下载)
#   - Python 3.10 + requests + yaml + jinja2(发布脚本用)
#   - git / unzip / wget / ca-certificates
#
# 瘦身策略(单 RUN,同层内删除不进 layer):
#   - 预装 build-tools;36.0.0(消除每个 job 在线下载)
#   - 构建后删 cmdline-tools/latest/lib(sdkmanager 实现包,运行时不调用)
#   - strip /usr/share/{doc,man}
# 工作流约定:不直接调 sdkmanager;需要新 SDK 组件时重建镜像。
# Gradle 版本升级:改 GRADLE_VERSION + GRADLE_DIST_HASH(本地跑一次 ./gradlew 后从
#   ~/.gradle/wrapper/dists/gradle-X.X.X-bin/<hash>/ 取 hash),重建镜像。
FROM eclipse-temurin:21-jdk-jammy

ARG CMDLINE_TOOLS=11076708
ARG GRADLE_VERSION=9.3.1
ARG GRADLE_DIST_HASH=23ovyewtku6u96viwx3xl3oks
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

# 预装 Gradle dist 到 wrapper 期望的路径(独立 layer,版本升级只重建此层)。
# wrapper 看到 .ok marker → 跳过下载;每容器从 image layer 拿只读副本,无锁冲突。
RUN mkdir -p /root/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin/${GRADLE_DIST_HASH} \
 && cd /root/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin/${GRADLE_DIST_HASH} \
 && wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
 && unzip -q gradle-${GRADLE_VERSION}-bin.zip \
 && rm gradle-${GRADLE_VERSION}-bin.zip \
 && touch gradle-${GRADLE_VERSION}-bin.zip.ok

WORKDIR /workspace
