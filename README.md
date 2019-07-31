# RxJavaFiberInterop
Library for interoperation between RxJava 3 and Project Loom's Fibers.

<a href='https://travis-ci.org/akarnokd/RxJavaFiberInterop/builds'><img src='https://travis-ci.org/akarnokd/RxJavaFiberInterop.svg?branch=master'></a>
[![codecov.io](http://codecov.io/github/akarnokd/RxJavaFiberInterop/coverage.svg?branch=master)](http://codecov.io/github/akarnokd/RxJavaFiberInterop?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.akarnokd/RxJavaFiberInterop/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.akarnokd/RxJavaFiberInterop)


# Components

## FiberInteroop

### create

Creates a `Flowable` from a generator callback, that can emit via `FiberEmitter`, run in a Fiber backed by the `computation` scheduler
and suspended automatically on downstream backpressure.

```java
FiberInterop.create(emitter -> {
    for (int i = 1; i <= 5; i++) {
         emitter.emit(1);
    }
})
.test()
.awaitDone(5, TimeUnit.SECONDS)
.assertResult(1, 2, 3, 4, 5);
```
