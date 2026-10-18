#!/usr/bin/env bash
# Linux x86-64 packaged consumers, using local Ivy or an explicit remote Maven repository.
# Usage: bash ci/consumer-smoke/run.sh VERSION [REPOSITORY]
set -euo pipefail
VERSION=${1:?pass the exact published ScalaCV version}
REPOSITORY=${2:-ivy2Local}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d "${TMPDIR:-${RUNNER_TEMP:-$HOME/.cache}}/scalacv-consumer.XXXXXXXX")
export COURSIER_CACHE="$WORK/cache"
printf 'Consumer evidence: %s\n' "$WORK"
java -version
# The natives coordinates a consumer adds by hand (the published POMs carry only the classifier-less
# opencv). Both smoke programs print the OpenCV artifact version baked into scalacv.Build, and the
# greps below pin it to this value: a Scala Steward bump of Deps.opencv that leaves this script behind
# fails here instead of silently testing the new library against the old natives.
OPENCV=4.14.0-1.5.14
OPENBLAS=0.3.34-1.5.14
CP=$(cs fetch --classpath --no-default -r "$REPOSITORY" -r https://repo.maven.apache.org/maven2 \
  "com.worxbend:scalacv_3:$VERSION" \
  "com.worxbend:scalacv-vision_3:$VERSION" \
  "com.worxbend:scalacv-graphs_3:$VERSION" \
  "com.worxbend:scalacv-zio_3:$VERSION" \
  "org.bytedeco:opencv:$OPENCV,classifier=linux-x86_64" \
  "org.bytedeco:openblas:$OPENBLAS,classifier=linux-x86_64")
javac --release 17 -cp "$CP" -d "$WORK" "$ROOT/ci/consumer-smoke/ConsumerSmoke.java"
# Preserve the existing Java smoke's teardown policy; no marker means failure.
out=$(java -cp "$WORK:$CP" ConsumerSmoke 2>&1) || true
printf '%s\n' "$out" | tee "$WORK/java.log"
grep -q '^CONSUMER-OK' "$WORK/java.log"
grep -q "^OPENCV-ARTIFACT=$OPENCV\$" "$WORK/java.log"
# Exact floor compiler, kept OFF the consumer runtime classpath.
COMPILER_CP=$(cs fetch --classpath org.scala-lang:scala3-compiler_3:3.3.8)
java -cp "$COMPILER_CP" dotty.tools.dotc.Main -java-output-version 17 \
  -classpath "$CP" -d "$WORK" "$ROOT/ci/consumer-smoke/ScalaConsumerSmoke.scala"
java -cp "$WORK:$CP" ScalaConsumerSmoke | tee "$WORK/scala.log"
grep -q '^SCALA-CONSUMER-OK' "$WORK/scala.log"
grep -q "^OPENCV-ARTIFACT=$OPENCV\$" "$WORK/scala.log"
