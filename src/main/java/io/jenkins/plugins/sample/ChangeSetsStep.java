package io.jenkins.plugins.sample;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.LauncherDecorator;
import hudson.console.ConsoleLogFilter;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitChangeSet;
import hudson.scm.ChangeLogSet;
import hudson.util.Secret;
import jenkins.scm.RunWithSCM;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChangeSetsStep extends Step {

    @DataBoundConstructor
    public ChangeSetsStep(){}

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(context);
    }

    public static class Execution extends AbstractStepExecutionImpl {

        private static final long serialVersionUID = 1;
        boolean hasFiles = false;

        private List<String> fileCache = new ArrayList<>();

        Execution(StepContext context) {
            super(context);
        }

        @Override public boolean start() throws Exception {
            StepContext context = getContext();
            Run<?,?> run = getContext().get(Run.class);
            if(run == null){
                return false;
            }

            TaskListener listener = getContext().get(TaskListener.class);

            Run<?, ?> previousRun = run.getPreviousBuild();
            if(previousRun != null && !previousRun.isBuilding() && !Result.SUCCESS.equals(previousRun.getResult())
            && previousRun instanceof RunWithSCM) {
                List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = ((RunWithSCM<?, ?>) previousRun).getChangeSets();
                changeSets.stream().filter(set -> {
                    listener.getLogger().println(set.getKind());
                    return true;
                }).forEach(set -> {
                    Object[] items = set.getItems();
                    if(items != null) {
                        for(Object obj : items) {
                            listener.getLogger().println(obj);

                            if(obj instanceof GitChangeSet) {
                                if(handle(listener, (GitChangeSet) obj, context)){
                                    hasFiles = true;
                                }
                            }
                        }
                    }
                });
            }

            if(run instanceof RunWithSCM) {
                List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = ((RunWithSCM<?, ?>) run).getChangeSets();
                changeSets.stream().filter(set -> {
                    listener.getLogger().println(set.getKind());
                    return true;
                }).forEach(set -> {
                    Object[] items = set.getItems();
                    if(items != null) {
                        for(Object obj : items) {
                            listener.getLogger().println(obj);

                            if(obj instanceof GitChangeSet) {
                                if(handle(listener, (GitChangeSet) obj, context)){
                                    hasFiles = true;
                                }
                            }
                        }
                    }
                });
            } else {
                listener.getLogger().println("not RunWithSCM");
            }

            if(!hasFiles) {
                listener.getLogger().println("no change files found");
                context.newBodyInvoker().withCallback(BodyExecutionCallback.wrap(context)).start();
            }

            return false;
        }

        private boolean handle(TaskListener listener, GitChangeSet changeSet, StepContext context) {
            Collection<GitChangeSet.Path> files = changeSet.getAffectedFiles();
            if(files == null || files.size() == 0) {
                return false;
            }

            files.forEach(file -> {
                listener.getLogger().println(file.getPath());
                Map<String, String> overrides = new HashMap();
                overrides.put("changePath", file.getPath());

                if(fileCache.contains(file.getPath())){
                    return;
                } else {
                    fileCache.add(file.getPath());
                }

                try {
                    context.newBodyInvoker().
                            withContext(EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), new Overrider(overrides))).
                            withCallback(BodyExecutionCallback.wrap(context)).start();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });

            return true;
        }

        @Override public void onResume() {}
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override public String getFunctionName() {
            return "withChangeSets";
        }

        @Override public String getDisplayName() {
            return "withChangeSets";
        }

        @Override public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(TaskListener.class, Run.class)));
        }

    }

    private static final class Overrider extends EnvironmentExpander {

        private static final long serialVersionUID = 1;

        private final Map<String, Secret> overrides = new HashMap<String,Secret>();

        Overrider(Map<String,String> overrides) {
            for (Map.Entry<String,String> override : overrides.entrySet()) {
                this.overrides.put(override.getKey(), Secret.fromString(override.getValue()));
            }
        }

        @Override public void expand(EnvVars env) throws IOException, InterruptedException {
            for (Map.Entry<String,Secret> override : overrides.entrySet()) {
                env.override(override.getKey(), override.getValue().getPlainText());
            }
        }

    }
}
