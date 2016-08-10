package com.github.jonpeterson.spring.mvc.versioning

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.jonpeterson.jackson.module.versioning.JsonSerializeToVersion
import com.github.jonpeterson.jackson.module.versioning.JsonVersionedModel
import com.github.jonpeterson.jackson.module.versioning.VersionedModelConverter
import com.github.jonpeterson.jackson.module.versioning.VersioningModule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    @JsonSubTypes([
        @JsonSubTypes.Type(SerializeToVersionFieldCar.class),
        @JsonSubTypes.Type(SerializeToVersionMethodCar.class)
    ])
    static abstract class Car {
        String make
        String model
        int year
        boolean used
    }

    static class SerializeToVersionFieldCar extends Car {
        @JsonSerializeToVersion
        String serializeToVersion
    }

    static class SerializeToVersionMethodCar extends Car {
        private String serializeToVersion

        @JsonSerializeToVersion
        String getSerializeToVersion() {
            return serializeToVersion
        }

        @JsonSerializeToVersion
        void setSerializeToVersion(String serializeToVersion) {
            this.serializeToVersion = serializeToVersion
        }
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

        @RequestMapping(value = '/byHeader', consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
        @VersionedResponseBody(defaultVersion = '3', headerName = 'v')
        Car byHeader(@RequestBody Car car) {
            car.make = 'somethingElse'
            return car
        }

        @RequestMapping(value = '/byParam', consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
        @VersionedResponseBody(defaultVersion = '3', queryParamName = 'v')
        Car byParam(@RequestBody Car car) {
            car.make = 'somethingElse'
            return car
        }

        @RequestMapping(value = '/byEither', consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
        @VersionedResponseBody(defaultVersion = '3', headerName = 'v', queryParamName = 'v')
        Car byEither(@RequestBody Car car) {
            car.make = 'somethingElse'
            return car
        }
    }


    /**************\
    |* Test cases *|
    \**************/

    private static INv1 = [model: 'honda:civic', year: 2016, new: 'true', modelVersion: '1']
    private static INv3 = [make: 'honda', model: 'civic', year: 2016, used: false, modelVersion: '3']
    private static OUTv1 = [model: 'somethingElse:civic', year: 2016, new: 'true', modelVersion: '1']
    private static OUTv3 = [make: 'somethingElse', model: 'civic', year: 2016, used: false, modelVersion: '3']

    def 'post, update, and return'() {
        given:
        inBody['@class'] = SerializeToVersionFieldCar.class.name

        expect:
        with(restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<Map>(inBody, new LinkedMultiValueMap<String, String>(headers)), Map)) {
            statusCodeValue == 200
            body == outBody
        }

        where:
        url             | headers    | inBody | outBody
        '/byHeader'     | [:]        | INv1   | OUTv3
        '/byHeader'     | [:]        | INv3   | OUTv3
        '/byHeader?v=1' | [:]        | INv1   | OUTv3
        '/byHeader?v=1' | [:]        | INv3   | OUTv3
        '/byHeader'     | [v: ['1']] | INv1   | OUTv1
        '/byHeader'     | [v: ['1']] | INv3   | OUTv1

        '/byParam'      | [:]        | INv1   | OUTv3
        '/byParam'      | [:]        | INv3   | OUTv3
        '/byParam?v=1'  | [:]        | INv1   | OUTv1
        '/byParam?v=1'  | [:]        | INv3   | OUTv1
        '/byParam'      | [v: ['1']] | INv1   | OUTv3
        '/byParam'      | [v: ['1']] | INv3   | OUTv3

        '/byEither'     | [:]        | INv1   | OUTv3
        '/byEither'     | [:]        | INv3   | OUTv3
        '/byEither?v=1' | [:]        | INv1   | OUTv1
        '/byEither?v=1' | [:]        | INv3   | OUTv1
        '/byEither'     | [v: ['1']] | INv1   | OUTv1
        '/byEither'     | [v: ['1']] | INv3   | OUTv1
    }
}
