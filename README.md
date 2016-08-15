# Spring MVC Model Versioning
Spring MVC binding for using [Jackson Model Versioning Module](https://github.com/jonpeterson/jackson-module-model-versioning).

## Example
*Note: This example is using Groovy for brevity, but it is not required.*

### Example data model versions
**Car data v1**
```json
{
  "model": "honda:civic",
  "year": 2016,
  "new": "true"
}
```

**Car data v2**
```json
{
  "make": "honda",
  "model": "civic",
  "year": 2016,
  "new": "true"
}
```

**Car data v3**
```json
{
  "make": "honda",
  "model": "civic",
  "year": 2016,
  "used": false
}
```

### Define the model POJO and version converters
**Create a POJO for the newest version of the data. Using the [Jackson Model Versioning Module](https://github.com/jonpeterson/jackson-module-model-versioning), annotate the model with the current version and specify the converter class to use when deserializing from a potentially old version of the model to the current version and the converter class to use when serializing from the current version to a potentially old version of the model. Also add a field (or getter/setter methods) that specify the version that the model to be serialized to.**
```groovy
@JsonVersionedModel(currentVersion = '3',
                    toCurrentConverterClass = ToCurrentCarConverter,
                    toPastConverterClass = ToPastCarConverter)
class Car {
    String make
    String model
    int year
    boolean used

    @JsonSerializeToVersion
    String serializeToVersion
}
```

**Create the "up" converter and provide logic for how old versions should be converted to the current version.**
```groovy
class ToCurrentCarConverter implements VersionedModelConverter {
    @Override
    def ObjectNode convert(ObjectNode modelData, String modelVersion,
                           String targetModelVersion, JsonNodeFactory nodeFactory) {

        // model version is an int
        def version = modelVersion as int

        // version 1 had a single 'model' field that combined 'make' and 'model' with a colon delimiter
        if(version <= 1) {
            def makeAndModel = modelData.get('model').asText().split(':')
            modelData.put('make', makeAndModel[0])
            modelData.put('model', makeAndModel[1])
        }

        // version 1-2 had a 'new' text field instead of a boolean 'used' field
        if(version <= 2)
            modelData.put('used', !Boolean.parseBoolean(modelData.remove('new').asText()))
    }
}
```

**Create the "down" converter and provide logic for how the current version should be converted to an old version.**
```groovy
class ToPastCarConverter implements VersionedModelConverter {

    @Override
    def ObjectNode convert(ObjectNode modelData, String modelVersion,
                           String targetModelVersion, JsonNodeFactory nodeFactory) {

        // model version is an int
        def version = modelVersion as int
        def targetVersion = targetModelVersion as int

        // version 1 had a single 'model' field that combined 'make' and 'model' with a colon delimiter
        if(targetVersion <= 1 && version > 1)
            modelData.put('model', "${modelData.remove('make').asText()}:${modelData.get('model').asText()}")

        // version 1-2 had a 'new' text field instead of a boolean 'used' field
        if(targetVersion <= 2 && version > 2)
            modelData.put('new', !modelData.remove('used').asBoolean() as String)
    }
}
```

### Set up a REST endpoint
**Configure the Jackson ObjectMapper used by Spring MVC to use the [Jackson Model Versioning Module](https://github.com/jonpeterson/jackson-module-model-versioning).**
```groovy
@Bean
Jackson2ObjectMapperBuilder objectMapperBuilder() {
    return Jackson2ObjectMapperBuilder.json().modulesToInstall(new VersioningModule())
}
```

**Create a REST endpoint**
```groovy
@RequestMapping(method = RequestMethod.POST,
                path = '/',
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
@VersionedResponseBody(defaultVersion = '2',
                       headerName = 'Model-Version',
                       queryParamName = 'modelVersion')
Car createCar(@RequestBody Car car) {
    return carRepository.insert(car)
}
```

### Test the endpoint
**All that's left is to test it out.**
```groovy
def restTemplate = new RestTemplate(
    messageConverters: [
        new MappingJackson2HttpMessageConverter(new ObjectMapper().registerModule(new VersioningModule()))
    ]
)

// POST version 1 JSON and request version 2 JSON response via URL query param
println restTemplate.postForObject(
    'http://localhost:8080/?modelVersion=2',
    '{"model": "honda:civic", "year": 2016, "new": "true", "modelVersion": "1"],
    String
)
// prints '{"make":"honda","model":"civic","year":2016,"new":"true","modelVersion":"2"}'

// POST version 1 JSON and request version 2 JSON response via HTTP header
println restTemplate.exchange(
    'http://localhost:8080/',
    HttpMethod.POST,
    new HttpEntity<String>(
        '{"model": "honda:civic", "year": 2016, "new": "true", "modelVersion": "1"]',
        new LinkedMultiValueMap<String, String>('Model-Version': '2')
    ),
    String
)
// prints '{"make":"honda","model":"civic","year":2016,"new":"true","modelVersion":"2"}'
```

### More Examples
See the tests under `src/test/groovy` for more.

## Compatibility
* Compiled for Java 6
* Tested with Spring 4.0.0.RELEASE - 4.3.0.RELEASE
* Uses a version of [Jackson Model Versioning Module](https://github.com/jonpeterson/jackson-module-model-versioning) which is tested with Jackson 2.2 - 2.8.

## Getting Started with Gradle
```groovy
dependencies {
    compile 'com.github.jonpeterson:spring-webmvc-model-versioning:1.0.0'
}
```

## Getting Started with Maven
```xml
<dependency>
    <groupId>com.github.jonpeterson</groupId>
    <artifactId>spring-webmvc-model-versioning</artifactId>
    <version>1.0.0</version>
</dependency>
```

## [JavaDoc](https://jonpeterson.github.io/docs/spring-webmvc-model-versioning/1.0.0/index.html)

## [Changelog](CHANGELOG.md)