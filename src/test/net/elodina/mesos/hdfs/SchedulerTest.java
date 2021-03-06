package net.elodina.mesos.hdfs;

import net.elodina.mesos.api.Master;
import net.elodina.mesos.api.Offer;
import net.elodina.mesos.api.Task;
import net.elodina.mesos.util.Period;
import net.elodina.mesos.util.Strings;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

import static org.junit.Assert.*;

public class SchedulerTest extends HdfsMesosTestCase {
    @Test
    public void onTaskStarted() {
        Task.Status status = new Task.Status("state:running");
        Node node = Nodes.addNode(new Node("0"));

        // no node
        Scheduler.$.onTaskStarted(null, status);
        assertEquals(1, schedulerDriver.killedTasks.size());
        schedulerDriver.killedTasks.clear();

        // unexpected states
        for (Node.State state : Arrays.asList(Node.State.IDLE, Node.State.STOPPING)) {
            node.state = state;
            node.initRuntime(new Offer("resources:[ports:0..10]"));

            Scheduler.$.onTaskStarted(node, status);
            assertEquals(state, node.state);

            assertEquals(1, schedulerDriver.killedTasks.size());
            schedulerDriver.killedTasks.clear();
        }

        // expected states
        for (Node.State state : Arrays.asList(Node.State.STARTING, Node.State.RUNNING, Node.State.RECONCILING)) {
            node.state = state;
            node.initRuntime(new Offer("resources:[ports:0..10]"));

            Scheduler.$.onTaskStarted(node, status);
            assertEquals(Node.State.RUNNING, node.state);
            assertEquals(0, schedulerDriver.killedTasks.size());
        }
    }

    @Test
    public void onTaskStopped() {
        Node node = Nodes.addNode(new Node("0"));
        Task.Status status = new Task.Status("state:finished");

        // no node
        Scheduler.$.onTaskStopped(null, status);

        // idle
        Scheduler.$.onTaskStopped(node, status);
        assertEquals(Node.State.IDLE, node.state);

        // expected states
        for (Node.State state : Arrays.asList(Node.State.STARTING, Node.State.RUNNING, Node.State.STOPPING, Node.State.RECONCILING)) {
            node.state = state;
            node.initRuntime(new Offer("resources:[ports:0..10]"));

            Scheduler.$.onTaskStopped(node, status);
            assertEquals(state == Node.State.STOPPING ? Node.State.IDLE : Node.State.STARTING, node.state);
            assertNull(node.runtime);
            assertNull(node.reservation);
        }
    }

    @Test
    public void acceptOffer() {
        Node nn = Nodes.addNode(new Node("nn", Node.Type.NAMENODE));
        nn.state = Node.State.RECONCILING;

        // reconciling
        assertEquals("reconciling", Scheduler.$.acceptOffer(new Offer()));

        // nothing to start
        nn.state = Node.State.IDLE;
        assertEquals("nothing to start", Scheduler.$.acceptOffer(new Offer()));

        // low resources
        nn.state = Node.State.STARTING;
        nn.cpus = 2;
        assertEquals("node nn: cpus < 2.0", Scheduler.$.acceptOffer(new Offer("resources:[cpus:0.1]")));

        // offer accepted
        assertEquals(null, Scheduler.$.acceptOffer(new Offer("resources:[cpus:2;mem:2048;ports:0..10]")));
        assertNotNull(nn.runtime);
        assertEquals(1, schedulerDriver.launchedTasks.size());
    }

    @Test
    public void launchTask() {
        Node node = Nodes.addNode(new Node("nn"));
        node.state = Node.State.STARTING;

        Scheduler.$.launchTask(node, new Offer("resources:[ports:0..10]"));
        assertEquals(1, schedulerDriver.launchedTasks.size());

        assertEquals(Node.State.STARTING, node.state);
        assertNotNull(node.runtime);
        assertNotNull(node.reservation);
    }

    @Test
    public void otherAttributes() {
        Node nn = Nodes.addNode(new Node("nn", Node.Type.NAMENODE));
        nn.initRuntime(new Offer("hostname:nn, resources:[ports:0..10]"));
        nn.runtime.attributes = Strings.parseMap("a=1,b=2");

        Node dn = Nodes.addNode(new Node("dn", Node.Type.DATANODE));
        dn.initRuntime(new Offer("hostname:dn, resources:[ports:0..10]"));
        dn.runtime.attributes = Strings.parseMap("a=3,b=4");

        Map<String,Collection<String>> attrs = Scheduler.$.otherAttributes();
        assertEquals(3, attrs.size());
        assertEquals(Arrays.asList("nn", "dn"), attrs.get("hostname"));
        assertEquals(Arrays.asList("1", "3"), attrs.get("a"));
        assertEquals(Arrays.asList("2", "4"), attrs.get("b"));
    }

    @Test
    public void checkMesosVersion() {
        // no version
        Scheduler.$.checkMesosVersion(new Master());
        assertTrue(schedulerDriver.stopped);

        // unsupported version
        schedulerDriver.stopped = false;
        Scheduler.$.checkMesosVersion(new Master("version:0.22.0"));
        assertTrue(schedulerDriver.stopped);

        // supported version
        schedulerDriver.stopped = false;
        Scheduler.$.checkMesosVersion(new Master("version:0.23.0"));
        assertFalse(schedulerDriver.stopped);
    }

    // Reconciler
    @Test
    public void Reconciler_isActive() {
        // not reconciling
        Scheduler.Reconciler reconciler = new Scheduler.Reconciler();
        assertFalse(reconciler.isActive());

        // reconciling
        Node nn = Nodes.addNode(new Node("nn", Node.Type.NAMENODE));
        nn.state = Node.State.RECONCILING;
        assertTrue(reconciler.isActive());
    }

    @Test
    public void Reconciler_start() {
        Date now = new Date();
        schedulerDriver.reconciledTasks.clear();

        Node nn = Nodes.addNode(new Node("nn", Node.Type.NAMENODE));
        nn.initRuntime(new Offer("resources:[ports:0..10]"));

        Node dn = Nodes.addNode(new Node("dn", Node.Type.DATANODE));
        dn.initRuntime(new Offer("resources:[ports:0..10]"));

        // start
        Scheduler.Reconciler reconciler = new Scheduler.Reconciler();
        reconciler.start(schedulerDriver, now);

        assertEquals(1, reconciler.getTries());
        assertEquals(now, reconciler.getLastTry());

        assertEquals(1, schedulerDriver.reconciledTasks.size());
        assertEquals("", schedulerDriver.reconciledTasks.get(0));

        assertEquals(Node.State.RECONCILING, nn.state);
        assertEquals(Node.State.RECONCILING, dn.state);
    }

    @Test
    public void Reconciler_proceed() {
        Date now = new Date();

        Node nn = Nodes.addNode(new Node("nn", Node.Type.NAMENODE));
        nn.initRuntime(new Offer("resources:[ports:0..10]"));
        nn.state = Node.State.RECONCILING;

        Node dn = Nodes.addNode(new Node("dn", Node.Type.DATANODE));
        dn.initRuntime(new Offer("resources:[ports:0..10]"));
        dn.state = Node.State.RECONCILING;

        Scheduler.Reconciler reconciler = new Scheduler.Reconciler(new Period("0"), 2);

        // !started
        schedulerDriver.reconciledTasks.clear();
        reconciler.proceed(schedulerDriver, now);

        assertEquals(0, reconciler.getTries());
        assertEquals(null, reconciler.getLastTry());

        // start & proceed 2/2
        reconciler.start(schedulerDriver, now);

        schedulerDriver.reconciledTasks.clear();
        reconciler.proceed(schedulerDriver, now);

        assertEquals(2, reconciler.getTries());
        assertEquals(now, reconciler.getLastTry());

        assertEquals(2, schedulerDriver.reconciledTasks.size());
        assertEquals(nn.runtime.taskId, schedulerDriver.reconciledTasks.get(0));
        assertEquals(dn.runtime.taskId, schedulerDriver.reconciledTasks.get(1));

        // proceed 3/2 - exceeds maxTries
        schedulerDriver.reconciledTasks.clear();
        reconciler.proceed(schedulerDriver, now);

        assertEquals(0, reconciler.getTries());
        assertEquals(null, reconciler.getLastTry());

        assertEquals(Node.State.STARTING, nn.state);
        assertEquals(null, nn.runtime);

        assertEquals(Node.State.STARTING, dn.state);
        assertEquals(null, dn.runtime);

        assertEquals(0, schedulerDriver.reconciledTasks.size());
    }
}
