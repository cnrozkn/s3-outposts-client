package io.github.cnrozkn.s3client.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.cnrozkn.s3client.model.FileEntry;
import io.github.cnrozkn.s3client.model.ObjectPage;
import io.github.cnrozkn.s3client.storage.BucketStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@SpringBootTest(
        properties = {
            "S3CLIENT_USERNAME=admin",
            "S3CLIENT_PASSWORD=test-password-0123",
            "management.server.port=",
            "s3-client.region=eu-west-1",
            "s3-client.upload-max-size=10B",
            "s3-client.buckets[0].name=ro",
            "s3-client.buckets[0].alias=ro--op-s3",
            "s3-client.buckets[1].name=rw",
            "s3-client.buckets[1].alias=rw--op-s3",
            "s3-client.buckets[1].capabilities=LIST,DETAIL,DOWNLOAD,PREVIEW,VERSIONS,UPLOAD,DELETE"
        })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class WebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BucketStorage storage;

    private static ResponseInputStream<GetObjectResponse> object(String body, GetObjectResponse.Builder response) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new ResponseInputStream<>(
                response.contentLength((long) bytes.length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    private static S3Exception s3Error(int status, String code) {
        return (S3Exception) S3Exception.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .build();
    }

    @Test
    void pagesNeedSignIn() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser
    void listingShowsTimesInTheServerZoneAndWriteButtonsOnlyWhereAllowed() throws Exception {
        Instant modified = Instant.parse("2026-03-04T05:06:07Z");
        when(storage.list(any(), any(), any(), any()))
                .thenReturn(new ObjectPage(
                        List.of(), List.of(new FileEntry("a.txt", "a.txt", 5, modified)), null, false, false));
        String shown = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(modified);

        mvc.perform(get("/buckets/rw"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(shown)))
                .andExpect(content().string(containsString("data-delete=\"a.txt\"")))
                .andExpect(content().string(containsString("id=\"upload-input\"")));
        mvc.perform(get("/buckets/ro"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-delete"))))
                .andExpect(content().string(not(containsString("id=\"upload-input\""))));
    }

    @Test
    @WithMockUser
    void pausedListingsSaySo() throws Exception {
        when(storage.list(any(), any(), any(), any()))
                .thenReturn(new ObjectPage(List.of(), List.of(), "Ftoken", false, true));

        mvc.perform(get("/buckets/rw"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Listing paused")))
                .andExpect(content().string(containsString("t=Ftoken")));
    }

    @Test
    @WithMockUser
    void readPagesRejectOtherMethods() throws Exception {
        mvc.perform(delete("/buckets/rw").with(csrf())).andExpect(status().isMethodNotAllowed());
    }

    @Test
    @WithMockUser
    void writesNeedACsrfToken() throws Exception {
        mvc.perform(post("/buckets/rw/delete").param("key", "a.txt")).andExpect(status().isForbidden());

        verify(storage, never()).delete(any(), any());
    }

    @Test
    @WithMockUser
    void writesNeedTheCapability() throws Exception {
        mvc.perform(post("/buckets/ro/delete").param("key", "a.txt").with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/buckets/ro/exists").param("key", "a.txt")).andExpect(status().isForbidden());

        verify(storage, never()).delete(any(), any());
    }

    @Test
    @WithMockUser
    void deleteGoesBackToTheFolder() throws Exception {
        mvc.perform(post("/buckets/rw/delete")
                        .param("key", "docs/a.txt")
                        .param("prefix", "docs/")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/buckets/rw?prefix=docs%2F"));

        verify(storage).delete("rw--op-s3", "docs/a.txt");
    }

    @Test
    @WithMockUser
    void uploadStreamsTheBody() throws Exception {
        mvc.perform(post("/buckets/rw/upload")
                        .param("key", "docs/a.txt")
                        .content("hello")
                        .contentType(MediaType.TEXT_PLAIN)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(storage).put(eq("rw--op-s3"), eq("docs/a.txt"), any(InputStream.class), eq(5L), eq("text/plain"));
    }

    @Test
    @WithMockUser
    void keysCannotAddLinesToTheAuditLog(CapturedOutput output) throws Exception {
        mvc.perform(post("/buckets/rw/upload")
                        .param("key", "a.txt\naction=delete key=forged")
                        .content("hello")
                        .contentType(MediaType.TEXT_PLAIN)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(output).contains("key=a.txt_action=delete key=forged").doesNotContain("\naction=delete");
    }

    @Test
    @WithMockUser
    void uploadDoesNotReplaceWithoutConsent() throws Exception {
        when(storage.exists("rw--op-s3", "a.txt")).thenReturn(true);

        mvc.perform(post("/buckets/rw/upload")
                        .param("key", "a.txt")
                        .content("hello")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .with(csrf()))
                .andExpect(status().isConflict());

        verify(storage, never()).put(any(), any(), any(), anyLong(), any());
    }

    @Test
    @WithMockUser
    void uploadOverTheLimitIsRefused() throws Exception {
        mvc.perform(post("/buckets/rw/upload")
                        .param("key", "a.txt")
                        .content("12345678901")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .with(csrf()))
                .andExpect(status().is(413));

        verify(storage, never()).put(any(), any(), any(), anyLong(), any());
    }

    @Test
    @WithMockUser
    void invalidNamesAreRefused() throws Exception {
        mvc.perform(post("/buckets/rw/folder").param("folder", "a/b").with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/buckets/rw/rename")
                        .param("key", "a.txt")
                        .param("target", "b/")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void renameDoesNotReplaceWithoutConsent() throws Exception {
        when(storage.exists("rw--op-s3", "b.txt")).thenReturn(true);

        mvc.perform(post("/buckets/rw/rename")
                        .param("key", "a.txt")
                        .param("target", "b.txt")
                        .with(csrf()))
                .andExpect(status().isConflict());

        verify(storage, never()).move(any(), any(), any());
    }

    @Test
    @WithMockUser
    void singleRangesGetPartialContent() throws Exception {
        when(storage.open("rw--op-s3", "a.bin", null, "bytes=0-3"))
                .thenReturn(object("abcd", GetObjectResponse.builder().contentRange("bytes 0-3/10")));

        mvc.perform(get("/buckets/rw/download").param("key", "a.bin").header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-3/10"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(content().string("abcd"));
    }

    @Test
    @WithMockUser
    void multipleRangesGetTheWholeObject() throws Exception {
        when(storage.open("rw--op-s3", "a.bin", null, null))
                .thenReturn(object("abcdefghij", GetObjectResponse.builder()));

        mvc.perform(get("/buckets/rw/download").param("key", "a.bin").header("Range", "bytes=0-1,4-5"))
                .andExpect(status().isOk())
                .andExpect(content().string("abcdefghij"));
    }

    @Test
    @WithMockUser
    void aRangeOutsideTheObjectIs416() throws Exception {
        when(storage.open(any(), any(), any(), any())).thenThrow(s3Error(416, "InvalidRange"));

        mvc.perform(get("/buckets/rw/download").param("key", "a.bin").header("Range", "bytes=100-"))
                .andExpect(status().is(416));
    }

    @Test
    @WithMockUser
    void previewsAreSandboxedPlainText() throws Exception {
        when(storage.open("rw--op-s3", "page.html", null, null))
                .thenReturn(object(
                        "<script>alert(1)</script>", GetObjectResponse.builder().contentType("text/html")));

        mvc.perform(get("/buckets/rw/preview").param("key", "page.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    @Test
    @WithMockUser
    void aSlowS3BecomesAGatewayTimeout() throws Exception {
        when(storage.list(any(), any(), any(), any())).thenThrow(ApiCallTimeoutException.create(30_000));

        mvc.perform(get("/buckets/rw")).andExpect(status().isGatewayTimeout());
    }

    @Test
    @WithMockUser
    void anS3DenialShowsTheErrorCode() throws Exception {
        when(storage.detail(any(), any())).thenThrow(s3Error(403, "AccessDenied"));

        mvc.perform(get("/buckets/rw/object").param("key", "a.txt"))
                .andExpect(status().isForbidden())
                .andExpect(status().reason("S3 returned AccessDenied"));
    }
}
