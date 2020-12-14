# Build runtime image.
FROM openjdk:11.0.9.1-jdk

ENV APP_DIR=/usr/src/app
WORKDIR $APP_DIR

# Install the app
COPY build/grpc-interop-testing $APP_DIR/

# Copy all logging profiles, use the default one
COPY logging*.properties $APP_DIR/
ENV JAVA_OPTS="-Djava.util.logging.config.file=$APP_DIR/logging-json.properties"

# Server
ENTRYPOINT ["bin/xds-test-server"]
