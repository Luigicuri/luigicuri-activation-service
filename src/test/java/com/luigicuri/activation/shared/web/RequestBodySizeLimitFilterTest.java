package com.luigicuri.activation.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

/** R8.5 for chunked requests, which carry no Content-Length and can only be limited while being read. */
class RequestBodySizeLimitFilterTest {

    private final RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(new ObjectMapper());

    @Test
    void chunkedBodyAboveTheLimitIsCutOffWhileReading() {
        var request = chunked(new byte[(int) RequestBodySizeLimitFilter.MAX_BODY_BYTES + 1]);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> req.getInputStream().readAllBytes()))
                .isInstanceOf(RequestBodySizeLimitFilter.PayloadTooLargeException.class);
    }

    @Test
    void chunkedBodyWithinTheLimitIsReadNormally() throws Exception {
        var body = new byte[(int) RequestBodySizeLimitFilter.MAX_BODY_BYTES];
        var bytesRead = new int[1];

        filter.doFilter(chunked(body), new MockHttpServletResponse(),
                (req, res) -> bytesRead[0] = req.getInputStream().readAllBytes().length);

        assertThat(bytesRead[0]).isEqualTo(body.length);
    }

    @Test
    void limitAlsoAppliesWhenTheBodyIsReadByteByByte() {
        var request = chunked(new byte[(int) RequestBodySizeLimitFilter.MAX_BODY_BYTES + 1]);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var in = req.getInputStream();
            while (in.read() != -1) {
                // consume one byte at a time, as some parsers do
            }
        })).isInstanceOf(RequestBodySizeLimitFilter.PayloadTooLargeException.class);
    }

    @Test
    void bodyWithinTheLimitCanBeReadByteByByteToTheEnd() throws Exception {
        var bytesRead = new int[1];

        filter.doFilter(chunked(new byte[] {1, 2, 3}), new MockHttpServletResponse(), (req, res) -> {
            var in = req.getInputStream();
            while (in.read() != -1) {
                bytesRead[0]++;
            }
        });

        assertThat(bytesRead[0]).isEqualTo(3);
    }

    @Test
    void limitedStreamDelegatesTheServletStreamContract() throws Exception {
        var delegate = mock(ServletInputStream.class);
        given(delegate.isFinished()).willReturn(true);
        given(delegate.isReady()).willReturn(false);
        var listener = mock(ReadListener.class);
        var request = new MockHttpServletRequest("POST", "/api/v1/requests") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                return delegate;
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var limited = req.getInputStream();
            assertThat(limited).isNotSameAs(delegate);
            assertThat(limited.isFinished()).isTrue();
            assertThat(limited.isReady()).isFalse();
            limited.setReadListener(listener);
        });

        verify(delegate).setReadListener(listener);
    }

    @Test
    void declaredContentLengthAboveTheLimitIsRejectedBeforeReading() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/requests");
        request.setContent(new byte[(int) RequestBodySizeLimitFilter.MAX_BODY_BYTES + 1]);
        var response = new MockHttpServletResponse();
        var chainCalled = new boolean[1];

        filter.doFilter(request, response, (req, res) -> chainCalled[0] = true);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chainCalled[0]).isFalse();
    }

    private static MockHttpServletRequest chunked(byte[] body) {
        var request = new MockHttpServletRequest("POST", "/api/v1/requests") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body);
        return request;
    }
}
