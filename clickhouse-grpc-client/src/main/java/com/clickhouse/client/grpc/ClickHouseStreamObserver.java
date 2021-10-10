package com.clickhouse.client.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import com.clickhouse.client.ClickHouseConfig;
import com.clickhouse.client.ClickHouseDataStreamFactory;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.data.ClickHousePipedStream;
import com.clickhouse.client.exception.ClickHouseException;
import com.clickhouse.client.grpc.impl.Exception;
import com.clickhouse.client.grpc.impl.LogEntry;
import com.clickhouse.client.grpc.impl.Progress;
import com.clickhouse.client.grpc.impl.Result;
import com.clickhouse.client.grpc.impl.Stats;
import com.clickhouse.client.logging.Logger;
import com.clickhouse.client.logging.LoggerFactory;

public class ClickHouseStreamObserver implements StreamObserver<Result> {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseStreamObserver.class);

    private final ClickHouseNode server;

    private final CountDownLatch startLatch;
    private final CountDownLatch finishLatch;

    private final ClickHousePipedStream stream;

    private final ClickHouseGrpcResponseSummary summary;

    private Throwable error;

    protected ClickHouseStreamObserver(ClickHouseConfig config, ClickHouseNode server) {
        this.server = server;

        this.startLatch = new CountDownLatch(1);
        this.finishLatch = new CountDownLatch(1);

        this.stream = ClickHouseDataStreamFactory.getInstance().createPipedStream(config);

        this.summary = new ClickHouseGrpcResponseSummary();

        this.error = null;
    }

    protected void checkClosed() {
        if (finishLatch.getCount() == 0) {
            throw new IllegalStateException("closed observer");
        }
    }

    protected void setError(Throwable error) {
        if (this.error == null) {
            this.error = error;
        }
    }

    protected boolean updateStatus(Result result) {
        summary.results.incrementAndGet();

        log.info(() -> {
            for (LogEntry e : result.getLogsList()) {
                String logLevel = e.getLevel().name();
                int index = logLevel.indexOf('_');
                if (index > 0) {
                    logLevel = logLevel.substring(index + 1);
                }
                log.info("%s.%s [ %s ] {%s} <%s> %s: %s", e.getTime(), e.getTimeMicroseconds(), e.getThreadId(),
                        e.getQueryId(), logLevel, e.getSource(), e.getText());
            }
        });

        boolean proceed = true;

        if (result.hasStats()) {
            Stats stats = result.getStats();
            summary.allocatedBytes.set(stats.getAllocatedBytes());
            summary.blocks.set(stats.getBlocks());
            summary.rows.set(stats.getRows());
            summary.rowsBeforeLimit.set(stats.getRowsBeforeLimit());
        }

        if (result.hasProgress()) {
            Progress prog = result.getProgress();
            summary.readBytes.set(prog.getReadBytes());
            summary.readRows.set(prog.getReadRows());
            summary.totalRowsToRead.set(prog.getTotalRowsToRead());
            summary.writeBytes.set(prog.getWrittenBytes());
            summary.writeRows.set(prog.getWrittenRows());
        }

        if (result.getCancelled()) {
            proceed = false;
            onError(new StatusException(Status.CANCELLED));
        } else if (result.hasException()) {
            proceed = false;
            Exception e = result.getException();
            log.error("Server error: Code=%s, %s", e.getCode(), e.getDisplayText());
            log.error(e.getStackTrace());

            if (error == null) {
                error = new ClickHouseException(result.getException().getCode(), result.getException().getDisplayText(),
                        null);
            }
        }

        return proceed;
    }

    public boolean isCompleted() {
        return finishLatch.getCount() == 0;
    }

    public boolean isCancelled() {
        return isCompleted() && error != null;
    }

    public ClickHouseGrpcResponseSummary getSummary() {
        return summary;
    }

    public Throwable getError() {
        return error;
    }

    @Override
    public void onNext(Result value) {
        try {
            checkClosed();

            log.trace("Got result: %s", value);

            // consume value in a worker thread might not be helpful
            if (updateStatus(value)) {
                try {
                    // TODO close output stream if value.getOutput().isEmpty()?
                    value.getOutput().writeTo(stream);
                } catch (IOException e) {
                    onError(e);
                }
            }
        } finally {
            startLatch.countDown();
        }
    }

    @Override
    public void onError(Throwable t) {
        try {
            log.error("Query failed", t);

            setError(t);
            try {
                stream.close();
            } catch (IOException e) {
                // ignore
            }
            checkClosed();
            // Status status = Status.fromThrowable(error = t);
        } finally {
            startLatch.countDown();
            finishLatch.countDown();
        }
    }

    @Override
    public void onCompleted() {
        log.trace("Query finished");

        try {
            stream.flush();
        } catch (IOException e) {
            if (error == null) {
                error = e;
            }
            log.error("Failed to flush output", e);
        } finally {
            startLatch.countDown();
            finishLatch.countDown();

            try {
                stream.close();
            } catch (IOException e) {
                log.warn("Failed to close output stream", e);
            }
        }
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return startLatch.await(timeout, unit);
    }

    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return finishLatch.await(timeout, unit);
    }

    public InputStream getInputStream() {
        return stream.getInput();
    }
}