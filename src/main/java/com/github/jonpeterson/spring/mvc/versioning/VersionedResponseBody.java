package com.github.jonpeterson.spring.mvc.versioning;

import org.springframework.web.bind.annotation.ResponseBody;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
//@ResponseBody
public @interface VersionedResponseBody {

    String defaultVersion();

    String headerName() default "";

    String queryParamName() default "";
}
