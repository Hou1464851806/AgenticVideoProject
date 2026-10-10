package com.agenticvideo.business.upload;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Silo protocol boundary; no media payload traverses the business API. */
@Component
public class MultipartStorage {
    private final S3Client client;
    private final software.amazon.awssdk.services.s3.presigner.S3Presigner presigner;
    private final String bucket;
    private final String origin;
    private final int listPageSize;
    private final int partUrlSeconds;
    private boolean initialized;

    public MultipartStorage(@Value("${storage.endpoint:http://localhost:9000}") String endpoint,
            @Value("${storage.public-endpoint:http://localhost:9000}") String publicEndpoint,
            @Value("${storage.access-key:unconfigured}") String accessKey,
            @Value("${storage.secret-key:unconfigured}") String secretKey,
            @Value("${storage.bucket:originals}") String bucket,
            @Value("${app.allowed-origin:http://localhost:8080}") String origin,
            @Value("${storage.list-page-size:100}") int listPageSize,
            @Value("${storage.part-url-seconds:300}") int partUrlSeconds) {
        this.bucket = bucket;
        this.origin = origin;
        if (listPageSize < 1 || listPageSize > 1000) throw new IllegalArgumentException("Invalid S3 page size");
        this.listPageSize = listPageSize;
        if (partUrlSeconds < 1 || partUrlSeconds > 300) throw new IllegalArgumentException("Invalid upload URL lifetime");
        this.partUrlSeconds = partUrlSeconds;
        presigner = software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        client = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true).overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5))).build();
    }

    private synchronized void initialize() {
        if (initialized) return;
        try { client.createBucket(b -> b.bucket(bucket)); }
        catch (S3Exception failure) {
            if (!List.of("BucketAlreadyOwnedByYou", "BucketAlreadyExists").contains(failure.awsErrorDetails().errorCode())) throw failure;
        }
        client.putBucketCors(b -> b.bucket(bucket).corsConfiguration(c -> c.corsRules(CORSRule.builder()
                .allowedOrigins(origin).allowedMethods("PUT", "GET", "HEAD").allowedHeaders("*")
                .exposeHeaders("ETag").maxAgeSeconds(300).build())));
        initialized = true;
    }

    public String initializeUpload(String key) {
        initialize();
        var existing = unfinishedUploads(key);
        if (existing.isEmpty()) {
            try { client.createMultipartUpload(b -> b.bucket(bucket).key(key).contentType("application/octet-stream")); }
            catch (software.amazon.awssdk.core.exception.SdkException uncertain) {
                existing = unfinishedUploads(key);
                if (existing.isEmpty()) throw uncertain;
            }
            existing = unfinishedUploads(key);
        }
        if (existing.isEmpty()) throw new IllegalStateException("Created multipart is not discoverable");
        existing.sort(java.util.Comparator.comparing(MultipartUpload::initiated).thenComparing(MultipartUpload::uploadId));
        for (int i = 1; i < existing.size(); i++) {
            String duplicate = existing.get(i).uploadId();
            client.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(duplicate));
        }
        return existing.get(0).uploadId();
    }
    private List<MultipartUpload> unfinishedUploads(String key) {
        List<MultipartUpload> existing = new ArrayList<>();
        String keyMarker = null;
        String uploadMarker = null;
        boolean more;
        do {
            var page = client.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).prefix(key)
                    .keyMarker(keyMarker).uploadIdMarker(uploadMarker).maxUploads(100).build());
            page.uploads().stream().filter(upload -> upload.key().equals(key)).forEach(existing::add);
            more = Boolean.TRUE.equals(page.isTruncated());
            keyMarker = page.nextKeyMarker();
            uploadMarker = page.nextUploadIdMarker();
        } while (more);
        return existing;
    }
    public String partUrl(String key, String uploadId, int number) {
        return presigner.presignUploadPart(b -> b.signatureDuration(Duration.ofSeconds(partUrlSeconds))
                .uploadPartRequest(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(number))).url().toString();
    }
    public int partUrlSeconds() { return partUrlSeconds; }
    public String playbackUrl(String key) {
        return presigner.presignGetObject(b -> b.signatureDuration(Duration.ofMinutes(5))
                .getObjectRequest(r -> r.bucket(bucket).key(key))).url().toString();
    }
    public List<Part> parts(String key, String uploadId) {
        List<Part> parts = new ArrayList<>();
        Integer marker = null;
        boolean more;
        do {
            var page = client.listParts(ListPartsRequest.builder().bucket(bucket).key(key).uploadId(uploadId)
                    .partNumberMarker(marker).maxParts(listPageSize).build());
            parts.addAll(page.parts());
            more = Boolean.TRUE.equals(page.isTruncated());
            marker = page.nextPartNumberMarker();
        } while (more);
        return parts;
    }
    public Long objectSize(String key) {
        try { return client.headObject(b -> b.bucket(bucket).key(key)).contentLength(); }
        catch (S3Exception failure) { if (failure.statusCode() == 404) return null; throw failure; }
    }
    public void deleteObject(String key) { client.deleteObject(b -> b.bucket(bucket).key(key)); }
    public void complete(String key, String uploadId, List<Part> parts) {
        var completed = parts.stream().map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build()).toList();
        client.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(u -> u.parts(completed)));
    }
    @jakarta.annotation.PreDestroy
    public void close() { presigner.close(); client.close(); }

}
