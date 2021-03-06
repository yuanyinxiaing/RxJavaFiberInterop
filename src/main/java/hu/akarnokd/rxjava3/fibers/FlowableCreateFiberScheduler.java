/*
 * Copyright 2019 David Karnok
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hu.akarnokd.rxjava3.fibers;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.reactivestreams.*;

import io.reactivex.rxjava3.core.*;
import io.reactivex.rxjava3.core.Scheduler.Worker;
import io.reactivex.rxjava3.internal.util.BackpressureHelper;

/**
 * Runs a generator callback on a Fiber backed by a Worker of the given scheduler
 * and signals events emitted by the generator considering any downstream backpressure.
 *
 * @param <T> the element type of the flow
 */
final class FlowableCreateFiberScheduler<T> extends Flowable<T> {

    final FiberGenerator<T> generator;

    final Scheduler scheduler;

    FlowableCreateFiberScheduler(FiberGenerator<T> generator, Scheduler scheduler) {
        this.generator = generator;
        this.scheduler = scheduler;
    }

    @Override
    protected void subscribeActual(Subscriber<? super T> s) {
        var worker = scheduler.createWorker();

        var executor = Executors.newUnboundedExecutor(Thread.builder().virtual(worker::schedule).factory());

        var parent = new WorkerCreateFiberSubscription<>(s, generator, worker, executor);
        s.onSubscribe(parent);

        // Ignoring the Fiber because it can lead to awkward interruptions if cancelled
        executor.submit(parent);
    }

    static final class WorkerCreateFiberSubscription<T> extends CreateFiberSubscription<T> {
        private static final long serialVersionUID = -8552685969992500057L;

        final Worker worker;

        ExecutorService executor;

        WorkerCreateFiberSubscription(Subscriber<? super T> downstream, FiberGenerator<T> generator, Worker worker, ExecutorService executor) {
            super(downstream, generator);
            this.worker = worker;
            this.executor = executor;
        }

        @Override
        protected void cleanup() {
            worker.dispose();
            var e = executor;
            executor = null;
            if (e != null) {
                e.close();
            }
        }
    }

    abstract static class CreateFiberSubscription<T> extends AtomicLong implements Subscription, Callable<Void>, FiberEmitter<T> {

        private static final long serialVersionUID = -6959205135542203083L;

        Subscriber<? super T> downstream;

        final FiberGenerator<T> generator;

        final ResumableFiber consumerReady;

        volatile boolean cancelled;

        static final Throwable STOP = new Throwable("Downstream cancelled");

        long produced;

        CreateFiberSubscription(Subscriber<? super T> downstream, FiberGenerator<T> generator) {
            this.downstream = downstream;
            this.generator = generator;
            this.consumerReady = new ResumableFiber();
        }

        protected abstract void cleanup();

        @Override
        public Void call() {
            try {
                try {
                    generator.generate(this);
                } catch (Throwable ex) {
                    if (ex != STOP && !cancelled) {
                        downstream.onError(ex);
                    }
                    return null;
                }
                if (!cancelled) {
                    downstream.onComplete();
                }
            } finally {
                downstream = null;
                cleanup();
            }
            return null;
        }

        @Override
        public void request(long n) {
            BackpressureHelper.add(this, n);
            consumerReady.resume();
        }

        @Override
        public void cancel() {
            cancelled = true;
            request(1);
        }

        @Override
        public void emit(T item) throws Throwable {
            Objects.requireNonNull(item, "item is null");
            var p = produced;
            while (get() == p && !cancelled) {
                consumerReady.await();
            }

            if (cancelled) {
                throw STOP;
            }

            downstream.onNext(item);
            produced = p + 1;
        }
    }
}
