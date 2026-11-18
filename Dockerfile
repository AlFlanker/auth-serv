FROM nexus3.otr.ru:6690/repository/eb-poi-bpo-images/red-soft/openjdk:25.0.2.0
ARG SOURCE_FILE
WORKDIR /identity-service
COPY **/build/libs/$SOURCE_FILE /identity-service/identity-service-app.jar
CMD ["/bin/bash"]
