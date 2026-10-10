package com.agenticvideo.business.upload;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/** Network fault fixture forwarding signed control requests to an actual Silo. */
final class SiloFaultProxy implements AutoCloseable {
    enum Fault { NONE, DROP_CREATE_RESPONSE, DROP_COMPLETE_RESPONSE, COMPLETE_EMBEDDED_ERROR }
    final java.util.concurrent.atomic.AtomicInteger partPages = new java.util.concurrent.atomic.AtomicInteger();
    final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
    private final ServerSocket listener;
    private final IntSupplier targetPort;
    SiloFaultProxy(IntSupplier targetPort) {
        this.targetPort = targetPort;
        try { listener = new ServerSocket(0, 20, InetAddress.getLoopbackAddress()); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        Thread acceptor = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket incoming = listener.accept();
                    Thread connection = new Thread(() -> forward(incoming));
                    connection.setDaemon(true);
                    connection.start();
                } catch (IOException failure) { if (!listener.isClosed()) throw new UncheckedIOException(failure); }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }
    String endpoint() { return "http://localhost:" + listener.getLocalPort(); }
    private void forward(Socket incoming) {
        try (incoming) {
            incoming.setSoTimeout(20000);
            var input = incoming.getInputStream();
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int terminator = 0;
            while (terminator != 0x0d0a0d0a) {
                int value = input.read();
                if (value == -1) return;
                headerBytes.write(value);
                terminator = (terminator << 8) | value;
                if (headerBytes.size() > 65536) throw new IOException("Oversized header");
            }
            String headers = headerBytes.toString(StandardCharsets.ISO_8859_1);
            String first = headers.substring(0, headers.indexOf("\r\n"));
            int length = 0;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).strip());
            }
            byte[] body = input.readNBytes(length);
            if (first.startsWith("GET ") && first.contains("uploadId=")) partPages.incrementAndGet();
            boolean create = first.startsWith("POST ") && first.contains("uploads");
            boolean complete = first.startsWith("POST ") && first.contains("uploadId=");
            Fault mode = fault.get();
            if (complete && mode == Fault.COMPLETE_EMBEDDED_ERROR) {
                byte[] error = "<Error><Code>InternalError</Code><Message>injected completion error</Message></Error>".getBytes(StandardCharsets.UTF_8);
                incoming.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nContent-Length: " + error.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                incoming.getOutputStream().write(error);
                return;
            }
            try (Socket upstream = new Socket("localhost", targetPort.getAsInt())) {
                upstream.setSoTimeout(20000);
                String forwarded = headers.replaceAll("(?i)Connection:[^\\r\\n]*\\r\\n", "");
                forwarded = forwarded.substring(0, forwarded.length()-2) + "Connection: close\r\n\r\n";
                upstream.getOutputStream().write(forwarded.getBytes(StandardCharsets.ISO_8859_1));
                upstream.getOutputStream().write(body);
                upstream.getOutputStream().flush();
                byte[] response = upstream.getInputStream().readAllBytes();
                boolean drop = (create && mode == Fault.DROP_CREATE_RESPONSE) || (complete && mode == Fault.DROP_COMPLETE_RESPONSE);
                if (drop && fault.compareAndSet(mode, Fault.NONE)) return;
                incoming.getOutputStream().write(response);
            }
        } catch (IOException ignored) { /* The caller observes the injected closed connection. */ }
    }
    @Override public void close() throws IOException { listener.close(); }
}
