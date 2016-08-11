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
        public String serializeToVersion
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
        inBody['@class'] = outBody['@class'] = clazz.name

        expect:
        with(restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<Map>(inBody, new LinkedMultiValueMap<String, String>(headers)), Map)) {
            statusCodeValue == 200
            body == outBody
        }

        where:
        url             | headers    | clazz                       | inBody | outBody
        '/byHeader'     | [:]        | SerializeToVersionFieldCar  | INv1   | OUTv3
        '/byHeader'     | [:]        | SerializeToVersionMethodCar | INv1   | OUTv3
        '/byHeader'     | [:]        | SerializeToVersionFieldCar  | INv3   | OUTv3
        '/byHeader'     | [:]        | SerializeToVersionMethodCar | INv3   | OUTv3
        '/byHeader?v=1' | [:]        | SerializeToVersionFieldCar  | INv1   | OUTv3
        '/byHeader?v=1' | [:]        | SerializeToVersionMethodCar | INv1   | OUTv3
        '/byHeader?v=1' | [:]        | SerializeToVersionFieldCar  | INv3   | OUTv3
        '/byHeader?v=1' | [:]        | SerializeToVersionMethodCar | INv3   | OUTv3
        '/byHeader'     | [v: ['1']] | SerializeToVersionFieldCar  | INv1   | OUTv1
        '/byHeader'     | [v: ['1']] | SerializeToVersionMethodCar | INv1   | OUTv1
        '/byHeader'     | [v: ['1']] | SerializeToVersionFieldCar  | INv3   | OUTv1
        '/byHeader'     | [v: ['1']] | SerializeToVersionMethodCar | INv3   | OUTv1

        '/byParam'      | [:]        | SerializeToVersionFieldCar  | INv1   | OUTv3
        '/byParam'      | [:]        | SerializeToVersionMethodCar | INv1   | OUTv3
        '/byParam'      | [:]        | SerializeToVersionFieldCar  | INv3   | OUTv3
        '/byParam'      | [:]        | SerializeToVersionMethodCar | INv3   | OUTv3
        '/byParam?v=1'  | [:]        | SerializeToVersionFieldCar  | INv1   | OUTv1
        '/byParam?v=1'  | [:]        | SerializeToVersionMethodCar | INv1   | OUTv1
        '/byParam?v=1'  | [:]        | SerializeToVersionFieldCar  | INv3   | OUTv1
        '/byParam?v=1'  | [:]        | SerializeToVersionMethodCar | INv3   | OUTv1
        '/byParam'      | [v: ['1']] | SerializeToVersionFieldCar  | INv1   | OUTv3
        '/byParam'      | [v: ['1']] | SerializeToVersionMethodCar | INv1   | OUTv3
        '/byParam'      | [v: ['1']] | SerializeToVersionFieldCar  | INv3   | OUTv3
        '/byParam'      | [v: ['1']] | SerializeToVersionMethodCar | INv3   | OUTv3

        '/byEither'     | [:]        | SerializeToVersionFieldCar  | INv1   | OUTv3
        '/byEither'     | [:]        | SerializeToVersionMethodCar | INv1   | OUTv3
        '/byEither'     | [:]        | SerializeToVersionFieldCar  | INv3   | OUTv3
        '/byEither'     | [:]        | SerializeToVersionMethodCar | INv3   | OUTv3
        '/byEither?v=1' | [:]        | SerializeToVersionFieldCar  | INv1   | OUTv1
        '/byEither?v=1' | [:]        | SerializeToVersionMethodCar | INv1   | OUTv1
        '/byEither?v=1' | [:]        | SerializeToVersionFieldCar  | INv3   | OUTv1
        '/byEither?v=1' | [:]        | SerializeToVersionMethodCar | INv3   | OUTv1
        '/byEither'     | [v: ['1']] | SerializeToVersionFieldCar  | INv1   | OUTv1
        '/byEither'     | [v: ['1']] | SerializeToVersionMethodCar | INv1   | OUTv1
        '/byEither'     | [v: ['1']] | SerializeToVersionFieldCar  | INv3   | OUTv1
        '/byEither'     | [v: ['1']] | SerializeToVersionMethodCar | INv3   | OUTv1
    }
}
