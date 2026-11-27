/* 用数据库和 MinIO 替身核对孤儿对象回收的三种模式及失败边界。 */

package com.pony.knowledge.service.storage;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pony.knowledge.mapper.DocumentMapper;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MinioReclaimServiceTest {

    private final MinioClient minio = mock(MinioClient.class);

    private final DocumentMapper documents = mock(DocumentMapper.class);

    /** 验证明示关闭优先于旧开关，未配置模式和旧开关时同样跳过扫描。 */
    @ParameterizedTest
    @CsvSource({"disabled, true", "'', false"})
    void disabledSkipsAllStorageAndDatabaseCalls(String mode, boolean enabled) {
        service(mode, enabled).reclaimOrphanObjects();

        verifyNoInteractions(minio, documents);
    }

    /** 验证预览列出符合宽限期且无引用的对象，旧删除开关同样受预览模式约束。 */
    @Test
    void dryRunReportsCandidatesWithoutRemovingObjects() throws Exception {
        when(documents.selectAllFilePaths()).thenReturn(List.of("referenced.pdf"));
        mockBucket(List.of(object("orphan.pdf", 25), object("referenced.pdf", 26), object("recent.pdf", 23)));

        List<String> messages = captureLogs(service("dry-run", true)::reclaimOrphanObjects);

        assertThat(messages).containsExactly(
                "孤儿对象预览 object=orphan.pdf bucket=ekip",
                "孤儿对象预览完成 bucket=ekip 宽限期=24h 候选数=1");
        verify(minio, never()).removeObject(any(RemoveObjectArgs.class));
    }

    /** 验证显式删除和兼容旧开关都只移除符合条件的对象。 */
    @ParameterizedTest
    @CsvSource({"delete, false", "' ', true"})
    void appliesDeletesForExplicitModeOrLegacyEnabled(String mode, boolean enabled) throws Exception {
        when(documents.selectAllFilePaths()).thenReturn(List.of("referenced.pdf"));
        mockBucket(List.of(object("orphan.pdf", 25), object("referenced.pdf", 26), object("recent.pdf", 23)));

        service(mode, enabled).reclaimOrphanObjects();

        ArgumentCaptor<RemoveObjectArgs> removed = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minio).removeObject(removed.capture());
        assertThat(removed.getValue().object()).isEqualTo("orphan.pdf");
        assertThat(removed.getValue().bucket()).isEqualTo("ekip");
    }

    /** 验证回收配置拼写错误时直接报错，避免隐式落入旧删除开关。 */
    @Test
    void rejectsInvalidExplicitMode() {
        assertThatThrownBy(() -> service("dryrun", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storage.reclaim.mode");
        verifyNoInteractions(minio, documents);
    }

    /** 验证无数据库对象引用时不扫描桶。 */
    @ParameterizedTest
    @ValueSource(strings = {"dry-run", "delete"})
    void skipsBucketWhenDatabaseHasNoReferences(String mode) {
        when(documents.selectAllFilePaths()).thenReturn(List.of());

        service(mode, false).reclaimOrphanObjects();

        verifyNoInteractions(minio);
    }

    /** 验证数据库读取出错时不扫描桶。 */
    @ParameterizedTest
    @ValueSource(strings = {"dry-run", "delete"})
    void skipsBucketWhenDatabaseLookupFails(String mode) {
        when(documents.selectAllFilePaths()).thenThrow(new IllegalStateException("database unavailable"));

        service(mode, false).reclaimOrphanObjects();

        verifyNoInteractions(minio);
    }

    /** 验证对象列表不完整时跳过整轮，已经读出的对象也不进入结果。 */
    @ParameterizedTest
    @ValueSource(strings = {"dry-run", "delete"})
    void ignoresPartialBucketListingOnFailure(String mode) throws Exception {
        when(documents.selectAllFilePaths()).thenReturn(List.of("referenced.pdf"));
        mockBucket(List.of(
                object("orphan.pdf", 25), new Result<Item>(new IllegalStateException("listing failed"))));

        List<String> messages = captureLogs(service(mode, false)::reclaimOrphanObjects);

        assertThat(messages).containsExactly("读取 MinIO 对象列表失败，跳过本轮回收扫描");
        verify(minio, never()).removeObject(any(RemoveObjectArgs.class));
    }

    /** 验证删除单个对象失败后仍会处理后续候选。 */
    @Test
    void continuesAfterOneObjectRemovalFails() throws Exception {
        when(documents.selectAllFilePaths()).thenReturn(List.of("referenced.pdf"));
        mockBucket(List.of(object("first.pdf", 25), object("second.pdf", 25)));
        doThrow(new IllegalStateException("object unavailable")).doNothing()
                .when(minio).removeObject(any(RemoveObjectArgs.class));

        service("delete", false).reclaimOrphanObjects();

        ArgumentCaptor<RemoveObjectArgs> removed = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minio, times(2)).removeObject(removed.capture());
        assertThat(removed.getAllValues()).extracting(RemoveObjectArgs::object)
                .containsExactly("first.pdf", "second.pdf");
    }

    /** 构造使用独立替身和固定宽限期的被测服务。 */
    private MinioReclaimService service(String mode, boolean enabled) {
        return new MinioReclaimService(minio, documents, "ekip", mode, enabled, 24);
    }

    /** 将已经构造好的列举结果交给 MinIO 客户端替身。 */
    private void mockBucket(List<Result<Item>> objects) {
        when(minio.listObjects(any(ListObjectsArgs.class))).thenReturn(objects);
    }

    /** 根据对象键及对象年龄构造一条 MinIO 列举结果。 */
    private static Result<Item> object(String key, long ageHours) {
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(key);
        when(item.lastModified()).thenReturn(ZonedDateTime.now().minusHours(ageHours));
        return new Result<>(item);
    }

    /** 执行一次被测扫描，返回此服务输出的格式化日志。 */
    private static List<String> captureLogs(Runnable scan) {
        Logger logger = (Logger) LoggerFactory.getLogger(MinioReclaimService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            scan.run();
            return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
