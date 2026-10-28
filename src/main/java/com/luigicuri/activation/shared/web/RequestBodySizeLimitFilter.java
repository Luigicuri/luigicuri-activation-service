package com.luigicuri.activation.shared.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luigicuri.activation.shared.error.Problems;

/**
 * Rejects bodies above 16 KB with 413 (R8.5); a valid request is well under 1 KB. Requests that declare
 * a larger Content-Length are refused before anything is read. Chunked requests (no Content-Length) are
 * cut off while being read, which surfaces as {@link PayloadTooLargeException}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    public static final long MAX_BODY_BYTES = 16 * 1024;

    private final ObjectMapper objectMapper;

    public RequestBodySizeLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            Problems.write(request, response, payloadTooLarge(), objectMapper);
            return;
        }
        chain.doFilter(declaredLength < 0 ? new SizeLimitedRequest(request) : request, response);
    }

    public static ProblemDetail payloadTooLarge() {
        return Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "Payload too large",
                "The request body must not exceed %d bytes.".formatted(MAX_BODY_BYTES));
    }

    /** Thrown while reading a chunked body that goes over the limit. */
    public static class PayloadTooLargeException extends IOException {

        public PayloadTooLargeException() {
            super("Request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }

    private static final class SizeLimitedRequest extends HttpServletRequestWrapper {

        SizeLimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            var delegate = super.getInputStream();
            return new ServletInputStream() {
                private long bytesRead;

                @Override
                public int read() throws IOException {
                    int value = delegate.read();
                    if (value != -1) {
                        count(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int read = delegate.read(buffer, offset, length);
                    if (read > 0) {
                        count(read);
                    }
                    return read;
                }

                private void count(int read) throws PayloadTooLargeException {
                    bytesRead += read;
                    if (bytesRead > MAX_BODY_BYTES) {
                        throw new PayloadTooLargeException();
                    }
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
