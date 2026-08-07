package com.nova.studio.storage;

import com.nova.studio.infra.RuntimeEnv;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WIN-24 (D-1 修复) — MinIO 降级时的 10s 失败缓存必须真正生效（ARCH E.4 / R-5）：
 * MinIO 不可达时，连续两次健康探测，第二次不得再发起 bucketExists 网络请求
 * （mock client 调用计数断言），否则降级期每次素材读写都会支付完整探测延迟。
 */
class MinioStorageServiceTest {

    private RuntimeEnv runtimeEnv;
    private MinioClient client;
    private MinioStorageService service;

    @BeforeEach
    void setUp() throws Exception {
        runtimeEnv = mock(RuntimeEnv.class);
        when(runtimeEnv.getString("MINIO_ENDPOINT", "")).thenReturn("http://minio:9000");
        when(runtimeEnv.getString("MINIO_ACCESS_KEY", "")).thenReturn("ak");
        when(runtimeEnv.getString("MINIO_SECRET_KEY", "")).thenReturn("sk");
        when(runtimeEnv.getString("MINIO_BUCKET", "nova-assets")).thenReturn("nova-assets");
        when(runtimeEnv.getString("MINIO_REGION", "us-east-1")).thenReturn("us-east-1");

        // 黑盒不可达：bucketExists 每次调用都抛异常（模拟端点无 RST、阻塞至 connectTimeout 的场景）
        client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new RuntimeException("minio unreachable"));

        service = spy(new MinioStorageService(runtimeEnv));
        when(service.clientOrNull()).thenReturn(client);
    }

    @Test
    void failureIsCachedTenSecondsNoNewProbeOnSecondHealthyCheck() throws Exception {
        assertThat(service.isHealthy()).isFalse();
        assertThat(service.isHealthy()).isFalse();

        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));
    }

    @Test
    void objectStorageManagerSkipsProbeWhileMinioDown() throws Exception {
        when(runtimeEnv.getString("MINIO_ENABLED", "true")).thenReturn("true");
        ObjectStorageManager manager =
                new ObjectStorageManager(runtimeEnv, mock(DiskStorageService.class), service);

        assertThat(manager.isMinioActive()).isFalse();
        assertThat(manager.isMinioActive()).isFalse();

        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));
    }
}
