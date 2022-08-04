SpringBoot KIE Server Image Builder Test
==========================================

You can read more about this project in this KIE blog entry: https://blog.kie.org/2022/08/testing-spring-boot-kie-server-images.html

Docker must be installed to run the tests.

How to run it
------------------------------

You can run the application by simply starting the default profile (-Pjib)

```
mvn clean install

```

or with the profile buildpack 

```
mvn clean install -Pbuildpack

```

Use `-DnoRemove` option for skipping the removal of the image and dive into it.