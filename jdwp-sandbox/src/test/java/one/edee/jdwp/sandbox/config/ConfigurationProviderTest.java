package one.edee.jdwp.sandbox.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationProviderTest {

	@Test
	void shouldProvideFullyInitializedConfig() throws Exception {
		ConfigurationProvider provider = new ConfigurationProvider();
		AtomicReference<Integer> timeout = new AtomicReference<>();

		// Initializer thread — calls getConfig() which assigns instance before init()
		Thread initializer = new Thread(() -> provider.getConfig());
		initializer.start();

		// Wait until the instance reference is assigned (but init() hasn't been called)
		provider.awaitAssignment();

		// Reader thread — reads the partially-constructed configuration
		Thread reader = new Thread(() -> {
			Configuration config = provider.getConfig();
			timeout.set(config.getTimeout());
		});
		reader.start();

		// The reader sees instance != null (first null check passes) and returns it
		// with timeout still at 0. Let init() proceed now.
		Thread.sleep(50); // Give reader time to read the partial state
		provider.releaseInit();

		reader.join(2000);
		initializer.join(2000);

		assertThat(timeout.get())
			.describedAs("Timeout should be fully initialized to 5000")
			.isEqualTo(5000); // Fails: gets 0 because reader saw partially-constructed object
	}
}
