# RESTEasy


## Note about this repo

This is a hopefully short-lived fork of [resteasy](https://github.com/resteasy/Resteasy) including 
[PR 2152](https://github.com/resteasy/Resteasy/pull/2152) and [PR 2153](https://github.com/resteasy/Resteasy/pull/2153). 

This project implements the JAX-RS-mod-qs specification which you can read [here](https://github.com/factsmission/jax-rs-spec/).

The master branch contains some more changes to allow selected components to be available as version `4.4.0-mod-qs` from the maven repository with URI https://raw.github.com/retog/Resteasy/mvn-repo/.

If you want to use these artifacts in your project, add the following to your project's `pom.xml` file:

```
    <repositories>
        <repository>
            <id>resteasy-mvn-repo</id>
            <url>https://raw.github.com/retog/Resteasy/mvn-repo/</url>
            <snapshots>
                <enabled>true</enabled>
                <updatePolicy>always</updatePolicy>
            </snapshots>
        </repository>
    </repositories>
```
---

[![Build Status](https://travis-ci.org/resteasy/Resteasy.svg?branch=master)](https://travis-ci.org/resteasy/Resteasy)

RESTEasy is a JBoss.org project aimed at providing productivity frameworks for developing client and server RESTful applications and services in Java.  It is mainly a JAX-RS implementation but you'll find some other experimental code in the repository.

The project page can be found at https://resteasy.jboss.org

## JAX-RS
RESTEasy is a JBoss project that provides various frameworks to help you build RESTful Web Services and RESTful Java applications. It is a portable implementation of the JAX-RS specification. JAX-RS is a JCP specification that provides a Java API for RESTful Web Services over the HTTP protocol.

Read the entire specification [here](https://github.com/jax-rs)

The RESTEasy code is in the github repository, (https://github.com/resteasy/Resteasy.git).

## Getting started with RESTEasy
- Read a [book](https://resteasy.github.io/books.html)
- Check out the [examples](https://github.com/resteasy/resteasy-examples) in the repository.
- Read the [documentation](https://resteasy.github.io/docs).

## Documentation

To read the documentation you can [read it online](https://resteasy.github.io/docs). This is generated automatically from the [maven build](https://github.com/resteasy/Resteasy/tree/master/jaxrs/docbook)

A wiki exists with historical documents. You can find it [here](https://developer.jboss.org/wiki/ResteasyWIKI)

## Issues
Issues are kept in [JIRA](https://issues.jboss.org/browse/RESTEASY).

## Build
After pulling down a clone of the RESTEasy repository run

	mvn install

Currently it can be built using JDK 1.8 and 11.
If you don't have the JBoss Nexus repository configured in your own local Maven settings, you might need to add enable the 'jboss-repository' profile:

       mvn -Pjboss-repository install


### Requirements
- Java Cryptography Extension [JCE](http://www.oracle.com/technetwork/java/javase/downloads/jce8-download-2133166.html)

## Contribute
You are most welcome to contribute to RESTEasy!

Read the [Contribution guidelines](./CONTRIBUTING.md)
