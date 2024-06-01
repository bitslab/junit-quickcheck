/*
 The MIT License

 Copyright (c) 2010-2020 Paul R. Holser, Jr.

 Permission is hereby granted, free of charge, to any person obtaining
 a copy of this software and associated documentation files (the
 "Software"), to deal in the Software without restriction, including
 without limitation the rights to use, copy, modify, merge, publish,
 distribute, sublicense, and/or sell copies of the Software, and to
 permit persons to whom the Software is furnished to do so, subject to
 the following conditions:

 The above copyright notice and this permission notice shall be
 included in all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package com.pholser.junit.quickcheck.runner;

import static com.pholser.junit.quickcheck.runner.PropertyFalsified.counterexampleFound;
import static java.util.stream.Collectors.toList;

import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.internal.GeometricDistribution;
import com.pholser.junit.quickcheck.internal.ParameterSampler;
import com.pholser.junit.quickcheck.internal.ParameterTypeContext;
import com.pholser.junit.quickcheck.internal.PropertyParameterContext;
import com.pholser.junit.quickcheck.internal.SeededValue;
import com.pholser.junit.quickcheck.internal.ShrinkControl;
import com.pholser.junit.quickcheck.internal.generator.GeneratorRepository;
import com.pholser.junit.quickcheck.internal.generator.PropertyParameterGenerationContext;
import com.pholser.junit.quickcheck.internal.sampling.ExhaustiveParameterSampler;
import com.pholser.junit.quickcheck.internal.sampling.TupleParameterSampler;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.internal.AssumptionViolatedException;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;
import org.slf4j.Logger;
import ru.vyarus.java.generics.resolver.GenericsResolver;
import ru.vyarus.java.generics.resolver.context.MethodGenericsContext;

class PropertyStatement extends Statement {
    private final FrameworkMethod method;
    private final TestClass testClass;
    private final GeneratorRepository repo;
    private final GeometricDistribution distro;
    private final List<AssumptionViolatedException> assumptionViolations;
    private final Logger logger;

    private int successes;
    private final String overrideTrials = System.getenv("OverrideNumOfTrials");
    private int trials;

    PropertyStatement(
        FrameworkMethod method,
        TestClass testClass,
        GeneratorRepository repo,
        GeometricDistribution distro,
        Logger logger) {

        this.method = method;
        this.testClass = testClass;
        this.repo = repo;
        this.distro = distro;
        assumptionViolations = new ArrayList<>();
        this.logger = logger;
    }

    @Override public void evaluate() throws Throwable {
        Property marker = method.getAnnotation(Property.class);
        ParameterSampler sampler = sampler(marker);
        ShrinkControl shrinkControl = new ShrinkControl(marker);

        MethodGenericsContext generics =
            GenericsResolver.resolve(testClass.getJavaClass())
                .method(method.getMethod());
        List<PropertyParameterGenerationContext> paramContexts =
            Arrays.stream(method.getMethod().getParameters())
                .map(p -> parameterContextFor(p, generics))
                .map(p -> new PropertyParameterGenerationContext(
                    p,
                    repo,
                    distro,
                    new SourceOfRandomness(new Random()),
                    sampler
                ))
                .collect(toList());

        long i = 0;
        Stream<List<SeededValue>> sample = sampler.sample(paramContexts);
        for (List<SeededValue> args :
                (Iterable<List<SeededValue>>) sample::iterator) {
            i++;
            property(args, shrinkControl).verify();
        }

        if (overrideTrials != null) {
            // display trial information
            System.out.printf("Actual number of trials ran %d of expected %d for %s in %s%n", i, trials, method, testClass);
            System.out.printf("JSONDATA::{\"overrideTrials\":%d, \"trialsExpected\":%d, \"trialsRan\":%d, \"method\":\"%s\", \"class\":\"%s\"}%n", Long.parseLong(overrideTrials), trials, i, method.getName(), testClass.getName());
        }

        if (successes == 0 && !assumptionViolations.isEmpty()) {
            throw new NoValuesSatisfiedPropertyAssumptions(
                assumptionViolations);
        }
    }

    private PropertyVerifier property(
        List<SeededValue> arguments,
        ShrinkControl shrinkControl)
        throws InitializationError {

        if (logger.isDebugEnabled()) {
            logger.debug(
                "Verifying property {} from {} with these values:",
                method.getName(),
                testClass.getName());
            logger.debug("{}", Arrays.deepToString(arguments.toArray()));
        }

        List<PropertyParameterGenerationContext> params =
            arguments.stream().map(SeededValue::parameter).collect(toList());
        Object[] args = arguments.stream().map(SeededValue::value).toArray();
        long[] seeds =
            arguments.stream().mapToLong(SeededValue::seed).toArray();

        return new PropertyVerifier(
            testClass,
            method,
            args,
            seeds,
            s -> ++successes,
            assumptionViolations::add,
            (e, action) -> {
                if (!shrinkControl.shouldShrink()) {
                    shrinkControl.onMinimalCounterexample()
                        .handle(args, action);
                    throw counterexampleFound(
                        method.getName(),
                        args,
                        seeds,
                        e);
                }

                try {
                    shrink(params, args, seeds, shrinkControl, e);
                } catch (AssertionError ex) {
                    throw ex;
                } catch (Throwable ex) {
                    throw new AssertionError(ex);
                }
            }
        );
    }

    private void shrink(
        List<PropertyParameterGenerationContext> params,
        Object[] args,
        long[] seeds,
        ShrinkControl shrinkControl,
        AssertionError failure)
        throws Throwable {

        new Shrinker(
            method,
            testClass,
            failure,
            shrinkControl)
            .shrink(params, args, seeds);
    }

    private PropertyParameterContext parameterContextFor(
        Parameter parameter,
        MethodGenericsContext generics) {

        return new PropertyParameterContext(
            ParameterTypeContext.forParameter(parameter, generics)
                .allowMixedTypes(true)
        ).annotate(parameter);
    }

    private ParameterSampler sampler(Property marker) {

        // Add ability to override using environment variable
        if (overrideTrials == null || overrideTrials.equals("-1")) {
            trials = marker.trials();
        } else {
            trials = Integer.parseInt(overrideTrials);
            System.out.println("Trials overridden to " + trials + " for " + method + " in " + testClass);
        }

        switch (marker.mode()) {
            case SAMPLING:
                return new TupleParameterSampler(trials);
            case EXHAUSTIVE:
                return new ExhaustiveParameterSampler(trials);
            default:
                throw new AssertionError(
                    "Don't recognize mode " + marker.mode());
        }
    }
}
