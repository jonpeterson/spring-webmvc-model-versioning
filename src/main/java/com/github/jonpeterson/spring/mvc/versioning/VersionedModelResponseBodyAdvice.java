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
        return returnType.hasMethodAnnotation(VersionedResponseBody.class);
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
            for(BeanPropertyDefinition beanPropertyDefinition: mapper.getDeserializationConfig().introspect(mapper.getTypeFactory().constructType(body.getClass())).findProperties()) {
                AnnotatedMember accessor = beanPropertyDefinition.getAccessor();
                if(accessor != null && accessor.hasAnnotation(JsonSerializeToVersion.class))
                    accessor.setValue(body, targetVersion);
            }
        } catch(Exception e) {
            throw new RuntimeException("unable to set the version of the response body model", e);
        }

        return body;
    }
}
