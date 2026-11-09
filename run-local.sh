#!/bin/sh
set -eu
classes="${TMPDIR:-/tmp}/checkout-schema-rollout-classes"
mkdir -p "$classes"
javac -d "$classes" src/*.java
exec java --add-modules jdk.httpserver -cp "$classes" CheckoutRolloutServer
