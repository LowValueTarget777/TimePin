FROM golang:1.26.11-alpine@sha256:ecb67f987aa1a57794c91d19ade7ae0209f2f0f5496aaef9e6ae85ddf92d02ab AS build
ENV GOTOOLCHAIN=local
WORKDIR /src
COPY go.mod go.sum ./
RUN test "$(go env GOVERSION)" = "$(awk '$1 == "toolchain" {print $2}' go.mod)" && go mod download
COPY calendar ./calendar
COPY contract ./contract
COPY internal ./internal
COPY cmd ./cmd
RUN mkdir -p /out/data /out/ca-root/etc/ssl/certs \
    && chmod 0755 /out/ca-root /out/ca-root/etc /out/ca-root/etc/ssl /out/ca-root/etc/ssl/certs \
    && cp /etc/ssl/certs/ca-certificates.crt /out/ca-root/etc/ssl/certs/ \
    && chmod 0644 /out/ca-root/etc/ssl/certs/ca-certificates.crt \
    && CGO_ENABLED=0 go build -buildvcs=false -trimpath -ldflags="-s -w" -o /out/ ./cmd/timepin ./cmd/timepinctl

FROM scratch
COPY LICENSE THIRD_PARTY_NOTICES.md /usr/share/doc/timepin/
COPY licenses/go /usr/share/doc/timepin/licenses/go
COPY licenses/container /usr/share/doc/timepin/licenses/container
COPY internal/server/web/holidays/LICENSE.txt /usr/share/doc/timepin/internal/server/web/holidays/LICENSE.txt
COPY --from=build /out/ca-root/ /
COPY --from=build /out/timepin /timepin
COPY --from=build /out/timepinctl /timepinctl
COPY --from=build --chown=65532:65532 /out/data /data
USER 65532:65532
ENV XDG_CONFIG_HOME=/data/.config
ENV SQLITE_TMPDIR=/data
EXPOSE 8080
ENTRYPOINT ["/timepin"]
CMD ["-listen", "0.0.0.0:8080", "-db", "/data/timepin.db"]
