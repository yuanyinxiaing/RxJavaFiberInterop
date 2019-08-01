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
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;

import org.reactivestreams.*;

import io.reactivex.*;
import io.reactivex.Scheduler.Worker;
import io.reactivex.internal.queue.SpscArrayQueue;
import io.reactivex.internal.util.BackpressureHelper;

final class FlowableTransformFiberScheduler<T, R> extends Flowable<R>
implements FlowableTransformer<T, R> {

    final Flowable<T> source;

    final FiberTransformer<T, R> transformer;

    final Scheduler scheduler;

    final int prefetch;

    FlowableTransformFiberScheduler(Flowable<T> source,
            FiberTransformer<T, R> transformer, Scheduler scheduler, int prefetch) {
        this.source = source;
        this.transformer = transformer;
        this.scheduler = scheduler;
        this.prefetch = prefetch;
    }

    @Override
    public Publisher<R> apply(Flowable<T> upstream) {
        return new FlowableTransformFiberScheduler<>(upstream, transformer, scheduler, prefetch);
    }

    @Override
    protected void subscribeActual(Subscriber<? super R> s) {
        var worker = scheduler.createWorker();
        var parent = new WorkerTransformFiberSubscriber<>(s, transformer, worker, prefetch);
        source.subscribe(parent);
        parent.setFiber(FiberScope.background().schedule(worker::schedule, parent));
    }

    static final class WorkerTransformFiberSubscriber<T, R> extends TransformFiberSubscriber<T, R> {

        private static final long serialVersionUID = 6360560993564811498L;

        final Worker worker;

        WorkerTransformFiberSubscriber(Subscriber<? super R> downstream,
                FiberTransformer<T, R> transformer, Worker worker,
                int prefetch) {
            super(downstream, transformer, prefetch);
            this.worker = worker;
        }

        @Override
        protected void cleanup() {
            worker.dispose();
        }
    }

    abstract static class TransformFiberSubscriber<T, R> extends AtomicLong
    implements FlowableSubscriber<T>, Subscription, FiberEmitter<R>, Callable<Void> {

        private static final long serialVersionUID = -4702456711290258571L;

        final Subscriber<? super R> downstream;

        final FiberTransformer<T, R> transformer;

        final int prefetch;

        final AtomicLong requested;

        final ResumableFiber producerReady;

        final ResumableFiber consumerReady;

        final SpscArrayQueue<T> queue;

        final AtomicReference<Object> fiber;

        Subscription upstream;

        volatile boolean done;
        Throwable error;

        volatile boolean cancelled;

        static final Throwable STOP = new Throwable("Downstream cancelled");

        long produced;

        TransformFiberSubscriber(Subscriber<? super R> downstream,
                FiberTransformer<T, R> transformer,
                int prefetch) {
            this.downstream = downstream;
            this.transformer = transformer;
            this.prefetch = prefetch;
            this.requested = new AtomicLong();
            this.producerReady = new ResumableFiber();
            this.consumerReady = new ResumableFiber();
            this.queue = new SpscArrayQueue<>(prefetch);
            this.fiber = new AtomicReference<>();
        }

        @Override
        public void onSubscribe(Subscription s) {
            upstream = s;
            downstream.onSubscribe(this);
            s.request(prefetch);
        }

        @Override
        public void onNext(T t) {
            queue.offer(t);
            if (getAndIncrement() == 0) {
                producerReady.resume();
            }
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            onComplete();
        }

        @Override
        public void onComplete() {
            done = true;
            if (getAndIncrement() == 0) {
                producerReady.resume();
            }
        }

        @Override
        public void emit(R item) throws Throwable {
            Objects.requireNonNull(item, "item is null");

            var p = produced;
            while (requested.get() == p && !cancelled) {
                consumerReady.await();
            }

            if (cancelled) {
                throw STOP;
            }

            downstream.onNext(item);

            produced = p + 1;
        }

        @Override
        public void request(long n) {
            BackpressureHelper.add(requested, n);
            consumerReady.resume();
        }

        @Override
        public void cancel() {
            cancelled = true;
            var f = fiber.getAndSet(this);
            if (f != null && f != this) {
                ((Fiber<?>)f).cancel();
            }
            cleanup();

            producerReady.resume();
            consumerReady.resume();
        }

        @Override
        public Void call() throws Exception {
            try {
                try {
                    var consumed = 0L;
                    var limit = prefetch - (prefetch >> 2);
                    var wip = 0L;

                    while (!cancelled) {
                        var d = done;
                        var v = queue.poll();
                        var empty = v == null;

                        if (d && empty) {
                            var ex = error;
                            if (ex != null) {
                                downstream.onError(ex);
                            } else {
                                downstream.onComplete();
                            }
                            break;
                        }

                        if (!empty) {

                            if (++consumed == limit) {
                                consumed = 0;
                                upstream.request(limit);
                            }

                            transformer.transform(v, this);

                            continue;
                        }

                        wip = addAndGet(-wip);
                        if (wip == 0L) {
                            producerReady.await();
                        }
                    }
                } catch (Throwable ex) {
                    if (ex != STOP && !cancelled) {
                        upstream.cancel();
                        downstream.onError(ex);
                    }
                    return null;
                }
            } finally {
                queue.clear();
                fiber.getAndSet(this);
                cleanup();
            }
            return null;
        }

        public void setFiber(Fiber<?> fiber) {
            if (this.fiber.get() != null || this.fiber.compareAndSet(null, fiber)) {
                if (this.fiber.get() != this) {
                    fiber.cancel();
                }
            }
        }

        protected abstract void cleanup();
    }
}
