package com.luigicuri.activation.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
