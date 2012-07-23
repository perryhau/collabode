package collabode.testing;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.*;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

import collabode.Application;
import collabode.Debug;

public class ContinuousTesting implements Runnable {
    
    private static final ContinuousTesting TESTER = new ContinuousTesting();
    
    /**
     * The continuous testing driver. Must be {@link Thread#start()}ed.
     */
    public static ContinuousTesting getTester() {
        return TESTER;
    }
    
    private final BlockingQueue<IProject> toRun = new LinkedBlockingQueue<IProject>();
    private final ConcurrentMap<ILaunch, CountDownLatch> latches = new ConcurrentHashMap<ILaunch, CountDownLatch>();
    private final Set<ITestRunSession> launched = Collections.synchronizedSet(new HashSet<ITestRunSession>());
    private final Set<IProject> broken = new HashSet<IProject>();
    
    public final IResourceChangeListener listener = new IResourceChangeListener() {
        public void resourceChanged(IResourceChangeEvent event) {
            try {
                event.getDelta().accept(new IResourceDeltaVisitor() {
                    public boolean visit(IResourceDelta delta) throws CoreException {
                        IProject project = delta.getResource().getProject();
                        if (project == null) { return true; }
                        runTests(project);
                        return false;
                    }
                });
            } catch (CoreException ce) {
                ce.printStackTrace(); // XXX
            }
        }
    };
    
    private ContinuousTesting() {
        DebugPlugin.getDefault().getLaunchManager().addLaunchListener(new ILaunchesListener2() {
            public void launchesRemoved(ILaunch[] launches) { }
            public void launchesAdded(ILaunch[] launches) { }
            public void launchesChanged(ILaunch[] launches) { }
            public void launchesTerminated(ILaunch[] launches) {
                for (ILaunch launch : launches) {
                    latches.putIfAbsent(launch, new CountDownLatch(0));
                    latches.get(launch).countDown();
                }
            }
        });
        JUnitCore.addTestRunListener(new TestRunListener() {
            @Override public void testCaseFinished(ITestCaseElement elt) {
                ProjectTestsOwner.of(elt.getTestRunSession().getLaunchedProject()).update(elt);
            }
            @Override public void sessionLaunched(ITestRunSession session) {
                launched.add(session);
            }
            @Override public void sessionStarted(ITestRunSession session) {
                ProjectTestsOwner.of(session.getLaunchedProject()).update(session);
                launched.remove(session);
            }
        });
        Coverage.addCoverageListener(new CoverageListener() {
            public void coverage(Coverage coverage) {
                ProjectTestsOwner.of(coverage.project).update(coverage);
            }
        });
    }
    
    /**
     * Schedule test execution for the given project.
     */
    public void runTests(IProject project) {
        toRun.add(project);
    }
    
    public void run() {
        while (true) {
            try {
                IProject project = toRun.take();
                
                Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, null);
                IJavaProject javaProject = JavaCore.create(project);
                if ( ! javaProject.exists()) { continue; }
                
                if (JUnitCore.findTestTypes(javaProject, null).length == 0) {
                    ProjectTestsOwner.of(javaProject).empty();
                    continue;
                }
                
                toRun.removeAll(Collections.singleton(project));
                
                try {
                    Debug.Entry debug = Debug.begin("runTests").add("project", project.getName());
                    ILaunch launch = launch(javaProject);
                    awaitTermination(launch);
                    debug.end();
                } catch (CoreException ce) {
                    ce.printStackTrace(); // XXX
                }
                
                for (ITestRunSession session : launched) {
                    IProject failed = session.getLaunchedProject().getProject();
                    if (broken.contains(failed)) {
                        System.err.println("Broken " + session.getTestRunName()); // XXX
                    } else {
                        System.err.println("Retry " + session.getProgressState() + " " + session.getTestRunName()); // XXX
                        broken.add(failed);
                        runTests(failed);
                    }
                }
                launched.clear();
            } catch (Exception e) {
                e.printStackTrace(); // XXX ... and madly soldier on
            }
        }
    }
    
    private ILaunch launch(IJavaProject project) throws CoreException, IOException, InterruptedException {
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type = mgr.getLaunchConfigurationType("collabode.testing.launchconfig");
        ILaunchConfigurationWorkingCopy config = type.newInstance(null, "Continuous Test " + project.getElementName());
        config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, project.getElementName());
        config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "");
        config.setAttribute("org.eclipse.jdt.junit.KEEPRUNNING_ATTR", false);
        config.setAttribute("org.eclipse.jdt.junit.CONTAINER", project.getHandleIdentifier());
        config.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit4");
        
        Coverage coverage = new Coverage(project);
        Map<String, String> env = new HashMap<String, String>();
        env.put(Coverage.PORT, Integer.toString(coverage.port));
        config.setAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, env);
        
        StringBuilder vmArgs = new StringBuilder();
        String policy = "'" + Application.bundleResourcePath("config/export/security.test.policy") + "'";
        vmArgs.append("-Dorg.eclipse.osgi.framework.internal.core.FrameworkSecurityManager "); // XXX does nothing?
        vmArgs.append("-Djava.security.manager -Djava.security.policy==" + policy + " ");
        // vmArgs.append("-Djava.security.debug=access:failure ");
        if (ProjectTestsOwner.of(project).isTestDriven()) {
            vmArgs.append("-javaagent:" + TestSupportInitializer.weaverPath() + " ");
            // vmArgs.append("-Daj.weaving.verbose=true -Dorg.aspectj.weaver.showWeaveInfo=true ");
        }
        vmArgs.append("-ea");
        config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, vmArgs.toString());
        
        return config.launch(ILaunchManager.RUN_MODE, null);
    }
    
    private void awaitTermination(ILaunch target) throws InterruptedException {
        latches.putIfAbsent(target, new CountDownLatch(1));
        boolean terminated = latches.get(target).await(1, TimeUnit.MINUTES);
        if ( ! terminated) {
            throw new InterruptedException("launch took too long"); // XXX actually, terminate it
        }
    }
}
