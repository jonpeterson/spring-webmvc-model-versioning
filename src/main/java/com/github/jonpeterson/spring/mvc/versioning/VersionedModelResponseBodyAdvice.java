/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Jon Peterson
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.jonpeterson.spring.mvc.versioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.github.jonpeterson.jackson.module.versioning.JsonSerializeToVersion;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@ControllerAdvice
public class VersionedModelResponseBodyAdvice implements ResponseBodyAdvice {
    private static final ObjectMapper mapper = new ObjectMapper();


    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return returnType.getMethodAnnotation(VersionedResponseBody.class) != null;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, Class selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
        VersionedResponseBody versionedResponseBody = returnType.getMethodAnnotation(VersionedResponseBody.class);
        String targetVersion = null;

        String queryParamName = versionedResponseBody.queryParamName();
        if(!queryParamName.isEmpty()) {
            List<String> queryParamValues = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().get(queryParamName);
            if(queryParamValues != null && !queryParamValues.isEmpty())
                targetVersion = queryParamValues.get(0);
        }

        if(targetVersion == null) {
            String headerName = versionedResponseBody.headerName();
            if(!headerName.isEmpty()) {
                List<String> headerValues = request.getHeaders().get(headerName);
                if(headerValues != null && !headerValues.isEmpty())
                    targetVersion = headerValues.get(0);
            }
        }

        if(targetVersion == null)
            targetVersion = versionedResponseBody.defaultVersion();

        try {
            for(BeanPropertyDefinition beanPropertyDefinition: mapper.getSerializationConfig().introspect(mapper.getTypeFactory().constructType(body.getClass())).findProperties()) {
                AnnotatedMember field = beanPropertyDefinition.getField();
                AnnotatedMember setter = beanPropertyDefinition.getSetter();
                if((field != null && field.hasAnnotation(JsonSerializeToVersion.class)) || (setter != null && setter.hasAnnotation(JsonSerializeToVersion.class))) {
                    if(setter != null)
                        setter.setValue(body, targetVersion);
                    else
                        field.setValue(body, targetVersion);
                }
            }
        } catch(Exception e) {
            throw new RuntimeException("unable to set the version of the response body model", e);
        }

        return body;
    }
}
