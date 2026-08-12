How to release
==============

* Make sure that `~/.m2/settings.xml` has your Sonatype credentials.
* Bump the version in one place: the `<revision>` property in the root `pom.xml`
  (all modules inherit it). You can also override it per build with
  `-Drevision=x.y.z` without editing any file.
* `mvn -pl .,capnp-jvm-core,capnp-jvm-serialization,capnp-jvm-serialization-bytebuffer clean deploy -P release`
  * The flatten-maven-plugin resolves `${revision}` in the deployed POMs.
  * The leading `.` deploys the parent POM (`capnproto-java`) too. It must be
    published: the module POMs inherit their dependency versions from the
    parent's `dependencyManagement`, so consumers cannot resolve them without
    it. `compiler`, `examples`, and `benchmark` are intentionally not deployed.
* The package should be available within ten minutes,
  but [it might take up to two hours](https://issues.sonatype.org/browse/OSSRH-13527?focusedCommentId=338745&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-338745) for it
  to become available at https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.capnproto%22
* Make a git tag.
