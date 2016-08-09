package com.github.jonpeterson.spring.mvc.versioning;

import com.github.jonpeterson.jackson.module.versioning.JsonSerializeToVersion;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ControllerAdvice
public class VersionedModelResponseBodyAdvice implements ResponseBodyAdvice {
    private static Map<Class, AnnotatedElement> cache = new HashMap<Class, AnnotatedElement>();

    private static AnnotatedElement getSerializeToVersion(Class clazz) {
        if(cache.containsKey(clazz))
            return cache.get(clazz);

        final List<AnnotatedElement> elements = new ArrayList<AnnotatedElement>();

        ReflectionUtils.doWithMethods(clazz, new ReflectionUtils.MethodCallback() {
            @Override
            public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if(method.isAnnotationPresent(JsonSerializeToVersion.class) && parameterTypes.length == 1 && parameterTypes[0] == String.class)
                    elements.add(method);
            }
        });

        ReflectionUtils.doWithFields(clazz, new ReflectionUtils.FieldCallback() {
            @Override
            public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
                if(field.isAnnotationPresent(JsonSerializeToVersion.class))
                    elements.add(field);
            }
        });

        if(elements.isEmpty())
            return null;
        if(elements.size() > 1)
            throw new RuntimeException("too many @JsonSerializeToVersion annotations on fields and setter methods in class '" + clazz + "'");

        AnnotatedElement only = elements.get(0);
        cache.put(clazz, only);
        return only;
    }


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

        AnnotatedElement element = getSerializeToVersion(body.getClass());

        try {
            if(element instanceof Field) {
                Field field = (Field)element;
                boolean accessible = field.isAccessible();
                if(!accessible)
                    field.setAccessible(true);
                field.set(body, targetVersion);
                if(!accessible)
                    field.setAccessible(false);
            } else if(element instanceof Method) {
                Method method = (Method)element;
                boolean accessible = method.isAccessible();
                if(!accessible)
                    method.setAccessible(true);
                method.invoke(body, targetVersion);
                if(!accessible)
                    method.setAccessible(false);
            }
        } catch(Exception e) {
            throw new RuntimeException("unable to set the version of the response body model", e);
        }

        return body;
    }
}
