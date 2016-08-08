package com.github.jonpeterson.spring.mvc.versioning

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.jonpeterson.jackson.module.versioning.JsonSerializeToVersion
import com.github.jonpeterson.jackson.module.versioning.JsonVersionedModel
import com.github.jonpeterson.jackson.module.versioning.VersionedModelConverter
import com.github.jonpeterson.jackson.module.versioning.VersioningModule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.web.WebMvcRegistrationsAdapter
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.MethodParameter
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.stereotype.Controller
import org.springframework.test.context.ContextConfiguration
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.HandlerMethodReturnValueHandler
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import spock.lang.Specification

@SpringBootTest(classes = TestApplication, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration
class VersioningTest extends Specification {

    /********************\
    |* Test model class *|
    \********************/

    @JsonVersionedModel(currentVersion = '3',
                        defaultSerializeToVersion = '2',
                        toCurrentConverterClass = ToCurrentCarConverter,
                        toPastConverterClass = ToPastCarConverter)
    static class Car {
        String make
        String model
        int year
        boolean used

        @JsonSerializeToVersion
        String serializeToVersion
    }


    /***********************************\
    |* Test versioned model converters *|
    \***********************************/

    static class ToCurrentCarConverter implements VersionedModelConverter {

        @Override
        def ObjectNode convert(ObjectNode modelData, String modelVersion, String targetModelVersion, JsonNodeFactory nodeFactory) {
            // model version is an int
            def version = modelVersion as int

            // version 1 had a single 'model' field that combined 'make' and 'model' with a colon delimiter; split
            if(version <= 1) {
                def makeAndModel = modelData.get('model').asText().split(':')
                modelData.put('make', makeAndModel[0])
                modelData.put('model', makeAndModel[1])
            }

            // version 1-2 had a 'new' text field instead of a boolean 'used' field; convert and invert
            if(version <= 2)
                modelData.put('used', !Boolean.parseBoolean(modelData.remove('new').asText()))

            return modelData
        }
    }

    static class ToPastCarConverter implements VersionedModelConverter {

        @Override
        def ObjectNode convert(ObjectNode modelData, String modelVersion, String targetModelVersion, JsonNodeFactory nodeFactory) {
            // model version is an int
            def version = modelVersion as int
            def targetVersion = targetModelVersion as int

            // version 1 had a single 'model' field that combined 'make' and 'model' with a colon delimiter; combine
            if(targetVersion <= 1 && version > 1)
                modelData.put('model', "${modelData.remove('make').asText()}:${modelData.get('model').asText()}")

            // version 1-2 had a 'new' text field instead of a boolean 'used' field; convert and invert
            if(targetVersion <= 2 && version > 2)
                modelData.put('new', !modelData.remove('used').asBoolean() as String)

            return modelData
        }
    }


    /********************\
    |* Test application *|
    \********************/

    @Autowired
    private TestRestTemplate restTemplate

    @SpringBootApplication
    @RestController
    static class TestApplication {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new VersioningModule())
        }

        @RequestMapping(value = '/1', consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
        Car postAndReturn1(@RequestParam(value = 'v', required = false) responseVersionUrlParam, @RequestHeader(value = 'v', required = false) responseVersionHeader, @RequestBody Car car) {
            car.make = 'somethingElse'
            if(responseVersionHeader)
                car.serializeToVersion = responseVersionHeader
            else if(responseVersionUrlParam)
                car.serializeToVersion = responseVersionUrlParam
            return car
        }

        @RequestMapping(value = '/2', consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
        @VersionedResponseBody(defaultVersion = '3', headerName = 'v', queryParamName = 'v') Car postAndReturn2(@RequestBody Car car) {
            car.make = 'somethingElse'
            return car
        }
    }


    /**************\
    |* Test cases *|
    \**************/

    def setup() {
        // override TestRestTemplate's error swallowing
        restTemplate.restTemplate.errorHandler = new DefaultResponseErrorHandler()
    }

    def 'abc'() {
        expect:
        def entity = new HttpEntity<Map>(inBody, new LinkedMultiValueMap<String, String>(headers))
        with(restTemplate.exchange(url, HttpMethod.POST, entity, Map)) {
            statusCodeValue == 200
            body == outBody
        }

        where:
        url      | headers    | inBody                                                             | outBody
        '/1'     | [:]        | [model: 'honda:civic', year: 2016, new: 'true', modelVersion: '1'] | [make: 'somethingElse', model: 'civic', year: 2016, new: 'true', modelVersion: '2']
        '/1?v=1' | [:]        | [model: 'honda:civic', year: 2016, new: 'true', modelVersion: '1'] | [model: 'somethingElse:civic', year: 2016, new: 'true', modelVersion: '1']
        '/1'     | [v: ['1']] | [model: 'honda:civic', year: 2016, new: 'true', modelVersion: '1'] | [model: 'somethingElse:civic', year: 2016, new: 'true', modelVersion: '1']

        '/2'     | [:]        | [model: 'honda:civic', year: 2016, new: 'true', modelVersion: '1'] | [make: 'somethingElse', model: 'civic', year: 2016, new: 'true', modelVersion: '2']
        '/2?v=1' | [:]        | [model: 'honda:civic', year: 2016, new: 'true', modelVersion: '1'] | [model: 'somethingElse:civic', year: 2016, new: 'true', modelVersion: '1']
        '/2'     | [v: ['1']] | [model: 'honda:civic', year: 2016, new: 'true', modelVersion: '1'] | [model: 'somethingElse:civic', year: 2016, new: 'true', modelVersion: '1']
    }
}
