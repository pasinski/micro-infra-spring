micro-deps-spring-test-config
=================

Default micro-deps Spring test configuration - both for JUnit and Spock

## Spock specifications' base classes

### Integration tests

Just extend the __IntegrationSpec__ specification and you're ready to go!

```groovy
class AcceptanceSpec extends IntegrationSpec {

}
```

That way you'll have:

* 'test' profile activated
* __org.springframework.web.context.WebApplicationContext__ loaded

### MVC integration tests

Just extend the __MvcIntegrationSpec__ specification and you're ready to go!

```groovy
class AcceptanceSpec extends MvcIntegrationSpec {

}
```

That way you'll have:

* 'test' profile activated
* __org.springframework.web.context.WebApplicationContext__ loaded
* Spring MVC test support enabled
* access to application context
* access to web application context

### MVC integration tests with [WireMock](http://wiremock.org/)

Just extend the __MvcWiremockIntegrationSpec__ specification and you're ready to go!

```groovy
class AcceptanceSpec extends MvcWiremockIntegrationSpec {

}
```
## JUnit specifications' base classes (since 0.4.3)

### Integration tests

Just extend the __IntegrationTest__ class and you're ready to go!

```groovy
class AcceptanceTest extends IntegrationTest {

}
```

That way you'll have:

* 'test' profile activated
* __org.springframework.web.context.WebApplicationContext__ loaded

### MVC integration tests

Just extend the __MvcIntegrationTest__ class and you're ready to go!

```groovy
class AcceptanceTest extends MvcIntegrationTest {

}
```

That way you'll have:

* 'test' profile activated
* __org.springframework.web.context.WebApplicationContext__ loaded
* Spring MVC test support enabled
* access to application context
* access to web application context

### MVC integration tests with [WireMock](http://wiremock.org/)

Just extend the __MvcWiremockIntegrationTest__ class and you're ready to go!

```groovy
class AcceptanceTest extends MvcWiremockIntegrationTest {

}
```

That way you'll have:

* 'test' profile activated
* __org.springframework.web.context.WebApplicationContext__ loaded
* Spring MVC test support enabled
* __WireMock__ server running
* access to application context
* access to web application context
* access to __stubInteraction()__ method that allows you to stub __WireMock__.

## Project origins

Project was merged from [micro-deps-spring-config standalone project](https://github.com/4finance/micro-deps-spring-config)
