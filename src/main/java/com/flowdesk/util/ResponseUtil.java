package com.flowdesk.util;

import com.flowdesk.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

public class ResponseUtil {

    private static final ObjectMapper objectMapper =
            new ObjectMapper();

    public static void writeErrorResponse(
            HttpServletResponse response,
            int status,
            String message) throws Exception {

        Result<Void> result =
                Result.fail(status, message);

        String json =
                objectMapper.writeValueAsString(result);

        response.setStatus(status);
        response.setContentType(
                "application/json;charset=UTF-8"
        );

        response.getWriter().write(json);
    }
}