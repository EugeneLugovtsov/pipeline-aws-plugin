package de.taimos.pipeline.aws.cloudformation.stacksets;

import com.amazonaws.client.builder.AwsSyncClientBuilder;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.model.Parameter;
import com.amazonaws.services.cloudformation.model.StackInstanceSummary;
import com.amazonaws.services.cloudformation.model.StackSetOperationPreferences;
import com.amazonaws.services.cloudformation.model.StackSetSummary;
import com.amazonaws.services.cloudformation.model.Tag;
import com.amazonaws.services.cloudformation.model.UpdateStackSetRequest;
import com.amazonaws.services.cloudformation.model.UpdateStackSetResult;
import de.taimos.pipeline.aws.AWSClientFactory;
import hudson.EnvVars;
import hudson.model.TaskListener;
import lombok.Builder;
import lombok.Value;
import org.assertj.core.api.Assertions;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

@RunWith(PowerMockRunner.class)
@PrepareForTest(
		value = AWSClientFactory.class,
		fullyQualifiedNames = "de.taimos.pipeline.aws.cloudformation.stacksets.*"
)
@PowerMockIgnore("javax.crypto.*")
public class CFNUpdateStackSetStepTest {

	@Rule
	private JenkinsRule jenkinsRule = new JenkinsRule();
	private CloudFormationStackSet stackSet;

	@Before
	public void setupSdk() throws Exception {
		stackSet = Mockito.mock(CloudFormationStackSet.class);
		PowerMockito.mockStatic(AWSClientFactory.class);
		PowerMockito.whenNew(CloudFormationStackSet.class)
				.withAnyArguments()
				.thenReturn(stackSet);
		AmazonCloudFormation cloudFormation = Mockito.mock(AmazonCloudFormation.class);
		PowerMockito.when(AWSClientFactory.create(Mockito.any(AwsSyncClientBuilder.class), Mockito.any(EnvVars.class)))
				.thenReturn(cloudFormation);
	}

	@Test
	public void createNonExistantStack() throws Exception {
		WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "testStepWithGlobalCredentials");
		Mockito.when(stackSet.exists()).thenReturn(false);
		job.setDefinition(new CpsFlowDefinition(""
				+ "node {\n"
				+ "  cfnUpdateStackSet(stackSet: 'foo')"
				+ "}\n", true)
		);
		jenkinsRule.assertBuildStatusSuccess(job.scheduleBuild2(0));

		PowerMockito.verifyNew(CloudFormationStackSet.class, Mockito.atLeastOnce())
				.withArguments(
						Mockito.any(AmazonCloudFormation.class),
						Mockito.eq("foo"),
						Mockito.any(TaskListener.class),
						Mockito.eq(SleepStrategy.EXPONENTIAL_BACKOFF_STRATEGY)
				);
		Mockito.verify(stackSet).create(Mockito.anyString(), Mockito.anyString(), Mockito.anyCollectionOf(Parameter.class), Mockito.anyCollectionOf(Tag.class), Mockito.isNull(String.class), Mockito.isNull(String.class));
	}

	@Test
	public void createNonExistantStackWithCustomAdminArn() throws Exception {
		WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "testStepWithGlobalCredentials");
		Mockito.when(stackSet.exists()).thenReturn(false);
		job.setDefinition(new CpsFlowDefinition(""
				+ "node {\n"
				+ "  cfnUpdateStackSet(stackSet: 'foo', administratorRoleArn: 'bar', executionRoleName: 'baz')"
				+ "}\n", true)
		);
		jenkinsRule.assertBuildStatusSuccess(job.scheduleBuild2(0));

		PowerMockito.verifyNew(CloudFormationStackSet.class, Mockito.atLeastOnce())
				.withArguments(
						Mockito.any(AmazonCloudFormation.class),
						Mockito.eq("foo"),
						Mockito.any(TaskListener.class),
						Mockito.eq(SleepStrategy.EXPONENTIAL_BACKOFF_STRATEGY)
				);
		Mockito.verify(stackSet).create(Mockito.anyString(), Mockito.anyString(), Mockito.anyCollectionOf(Parameter.class), Mockito.anyCollectionOf(Tag.class), Mockito.eq("bar"), Mockito.eq("baz"));
	}

	@Test
	public void updateExistantStack() throws Exception {
		WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "cfnTest");
		Mockito.when(stackSet.exists()).thenReturn(true);
		String operationId = UUID.randomUUID().toString();
		Mockito.when(stackSet.update(Mockito.anyString(), Mockito.anyString(), Mockito.any(UpdateStackSetRequest.class)))
				.thenReturn(new UpdateStackSetResult()
						.withOperationId(operationId)
				);
		job.setDefinition(new CpsFlowDefinition(""
				+ "node {\n"
				+ "  cfnUpdateStackSet(stackSet: 'foo', pollInterval: 27, params: ['foo=bar'], paramsFile: 'params.json')"
				+ "}\n", true)
		);
		try (PrintWriter writer = new PrintWriter(jenkinsRule.jenkins.getWorkspaceFor(job).child("params.json").write())) {
			writer.println("[{\"ParameterKey\": \"foo1\", \"ParameterValue\": \"25\"}]");
		}
		jenkinsRule.assertBuildStatusSuccess(job.scheduleBuild2(0));

		PowerMockito.verifyNew(CloudFormationStackSet.class, Mockito.atLeastOnce())
				.withArguments(
						Mockito.any(AmazonCloudFormation.class),
						Mockito.eq("foo"),
						Mockito.any(TaskListener.class),
						Mockito.eq(SleepStrategy.EXPONENTIAL_BACKOFF_STRATEGY)
				);
		ArgumentCaptor<UpdateStackSetRequest> requestCapture = ArgumentCaptor.forClass(UpdateStackSetRequest.class);
		Mockito.verify(stackSet).update(Mockito.anyString(), Mockito.anyString(), requestCapture.capture());
		Assertions.assertThat(requestCapture.getValue().getParameters()).containsExactlyInAnyOrder(
				new Parameter()
						.withParameterKey("foo")
						.withParameterValue("bar"),
				new Parameter()
						.withParameterKey("foo1")
						.withParameterValue("25")
		);

		Mockito.verify(stackSet).waitForOperationToComplete(operationId, Duration.ofMillis(27));
	}

	@Test
	public void updateExistingStackStackSetWithOperationPreferences() throws Exception {
		WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "cfnTest");
		Mockito.when(stackSet.exists()).thenReturn(true);
		String operationId = UUID.randomUUID().toString();
		Mockito.when(stackSet.update(Mockito.anyString(), Mockito.anyString(), Mockito.any(UpdateStackSetRequest.class)))
				.thenReturn(new UpdateStackSetResult()
						.withOperationId(operationId)
				);
		job.setDefinition(new CpsFlowDefinition(""
				+ "node {\n"
				+ "  cfnUpdateStackSet(stackSet: 'foo', operationPreferences: [failureToleranceCount: 5, regionOrder: ['us-west-2'], failureTolerancePercentage: 17, maxConcurrentCount: 18, maxConcurrentPercentage: 34])"
				+ "}\n", true)
		);
		jenkinsRule.assertBuildStatusSuccess(job.scheduleBuild2(0));

		PowerMockito.verifyNew(CloudFormationStackSet.class, Mockito.atLeastOnce())
				.withArguments(
						Mockito.any(AmazonCloudFormation.class),
						Mockito.eq("foo"),
						Mockito.any(TaskListener.class),
						Mockito.eq(SleepStrategy.EXPONENTIAL_BACKOFF_STRATEGY)
				);
		ArgumentCaptor<UpdateStackSetRequest> requestCapture = ArgumentCaptor.forClass(UpdateStackSetRequest.class);
		Mockito.verify(stackSet).update(Mockito.anyString(), Mockito.anyString(), requestCapture.capture());

		Assertions.assertThat(requestCapture.getValue().getOperationPreferences()).isEqualTo(new StackSetOperationPreferences()
				.withFailureToleranceCount(5)
                .withRegionOrder("us-west-2")
				.withFailureTolerancePercentage(17)
				.withMaxConcurrentCount(18)
				.withMaxConcurrentPercentage(34)
		);

		Mockito.verify(stackSet).waitForOperationToComplete(operationId, Duration.ofSeconds(1));
	}

	@Test
	public void updateExistingStackWithCustomAdminRole() throws Exception {
		WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "cfnTest");
		Mockito.when(stackSet.exists()).thenReturn(true);
		String operationId = UUID.randomUUID().toString();
		Mockito.when(stackSet.update(Mockito.anyString(), Mockito.anyString(), Mockito.any(UpdateStackSetRequest.class)))
				.thenReturn(new UpdateStackSetResult()
						.withOperationId(operationId)
				);
		job.setDefinition(new CpsFlowDefinition(""
				+ "node {\n"
				+ "  cfnUpdateStackSet(stackSet: 'foo', administratorRoleArn: 'bar', executionRoleName: 'baz')"
				+ "}\n", true)
		);
		jenkinsRule.assertBuildStatusSuccess(job.scheduleBuild2(0));

		PowerMockito.verifyNew(CloudFormationStackSet.class, Mockito.atLeastOnce())
				.withArguments(
						Mockito.any(AmazonCloudFormation.class),
						Mockito.eq("foo"),
						Mockito.any(TaskListener.class),
						Mockito.eq(SleepStrategy.EXPONENTIAL_BACKOFF_STRATEGY)
				);
		Mockito.verify(stackSet).update(Mockito.anyString(), Mockito.anyString(), Mockito.any(UpdateStackSetRequest.class));
	}

	@Test
	public void doNotCreateNonExistantStack() throws Exception {
		WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "cfnTest");
		Mockito.when(stackSet.exists()).thenReturn(false);
		job.setDefinition(new CpsFlowDefinition(""
				+ "node {\n"
				+ "  cfnUpdateStackSet(stackSet: 'foo', create: false)"
				+ "}\n", true)
		);
		jenkinsRule.assertBuildStatusSuccess(job.scheduleBuild2(0));

		PowerMockito.verifyNew(CloudFormationStackSet.class, Mockito.atLeastOnce())
				.withArguments(
						Mockito.any(AmazonCloudFormation.class),
						Mockito.eq("foo"),
						Mockito.any(TaskListener.class),
						Mockito.eq(SleepStrategy.EXPONENTIAL_BACKOFF_STRATEGY)
				);
		Mockito.verify(stackSet, Mockito.never()).create(Mockito.anyString(), Mockito.anyString(), Mockito.anyCollectionOf(Parameter.class), Mockito.anyCollectionOf(Tag.class), Mockito.isNull(String.class), Mockito.isNull(String.class));
	}

	@Test
	public void updateWithRegionBatches() throws Exception {
		WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "cfnTest");
		Mockito.when(stackSet.exists()).thenReturn(true);
		String operationId = UUID.randomUUID().toString();
		Mockito.when(stackSet.update(Mockito.anyString(), Mockito.anyString(), Mockito.any(UpdateStackSetRequest.class)))
				.thenReturn(new UpdateStackSetResult()
						.withOperationId(operationId)
				);
		Mockito.when(stackSet.findStackSetInstances()).thenReturn(asList(
				new StackInstanceSummary().withAccount("a1").withRegion("r1"),
				new StackInstanceSummary().withAccount("a2").withRegion("r1"),
				new StackInstanceSummary().withAccount("a2").withRegion("r2"),
				new StackInstanceSummary().withAccount("a3").withRegion("r3")
		));
		job.setDefinition(new CpsFlowDefinition(""
				+ "node {\n"
				+ "  cfnUpdateStackSet(stackSet: 'foo'," +
				"       pollInterval: 27," +
				"       batchingOptions: [" +
				"         regions: true" +
				"       ]" +
				"    )"
				+ "}\n", true)
		);
		jenkinsRule.assertBuildStatusSuccess(job.scheduleBuild2(0));

		PowerMockito.verifyNew(CloudFormationStackSet.class, Mockito.atLeastOnce())
				.withArguments(
						Mockito.any(AmazonCloudFormation.class),
						Mockito.eq("foo"),
						Mockito.any(TaskListener.class),
						Mockito.eq(SleepStrategy.EXPONENTIAL_BACKOFF_STRATEGY)
				);
		ArgumentCaptor<UpdateStackSetRequest> requestCapture = ArgumentCaptor.forClass(UpdateStackSetRequest.class);
		Mockito.verify(stackSet, Mockito.times(3)).update(Mockito.anyString(), Mockito.anyString(), requestCapture.capture());
		Map<String, List<String>> capturedRegionAccounts = requestCapture.getAllValues()
				.stream()
				.flatMap(summary -> summary.getRegions()
						.stream()
						.flatMap(region -> summary.getAccounts().stream()
								.map(accountId -> RegionAccountIdTuple.builder().accountId(accountId).region(region).build())
						))
				.collect(Collectors.groupingBy(RegionAccountIdTuple::getRegion, Collectors.mapping(RegionAccountIdTuple::getAccountId, Collectors.toList())));
		Assertions.assertThat(capturedRegionAccounts).containsAllEntriesOf(new HashMap<String, List<String>>() {
			{
				put("r1", asList("a1", "a2"));
				put("r2", singletonList("a2"));
				put("r3", singletonList("a3"));
			}
		});

		Mockito.verify(stackSet, Mockito.times(3)).waitForOperationToComplete(Mockito.any(), Mockito.any());
	}

	@Value
	@Builder
	private static class RegionAccountIdTuple {
		String region, accountId;
	}
}
