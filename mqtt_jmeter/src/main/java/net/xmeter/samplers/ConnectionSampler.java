package net.xmeter.samplers;

import java.text.MessageFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.apache.log.Priority;
import org.fusesource.mqtt.client.Future;
import org.fusesource.mqtt.client.FutureConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;

import net.xmeter.Util;

public class ConnectionSampler extends AbstractMQTTSampler
		implements TestStateListener, ThreadListener, Interruptible, SampleListener {
	private transient static Logger logger = LoggingManager.getLoggerForClass();
	private transient MQTT mqtt = new MQTT();
	private transient FutureConnection connection = null;
	private boolean interrupt = false;
	//Declare it as static, for the instance variable will be set to initial value 0 in testEnded method.
	//The static value will not be reset.
	private static int keepTime = 0;
	
	private static AtomicBoolean sleepFlag = new AtomicBoolean(false);
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1859006013465470528L;

	@Override
	public boolean isKeepTimeShow() {
		return true;
	}
	@Override
	public SampleResult sample(Entry entry) {
		SampleResult result = new SampleResult();
		result.setSampleLabel(getName());
		try {
			if (!DEFAULT_PROTOCOL.equals(getProtocol())) {
				mqtt.setSslContext(Util.getContext(this));
			}
			
			mqtt.setHost(getProtocol().toLowerCase() + "://" + getServer() + ":" + getPort());
			mqtt.setKeepAlive((short) Integer.parseInt(getConnKeepAlive()));
			
			String clientId = null;
			if(isClientIdSuffix()) {
				clientId = Util.generateClientId(getConnPrefix());
			} else {
				clientId = getConnPrefix();
			}
			
			mqtt.setClientId(clientId);
			mqtt.setConnectAttemptsMax(Integer.parseInt(getConnAttamptMax()));
			mqtt.setReconnectAttemptsMax(Integer.parseInt(getConnReconnAttamptMax()));

			if (!"".equals(getUserNameAuth().trim())) {
				mqtt.setUserName(getUserNameAuth());
			}
			if (!"".equals(getPasswordAuth().trim())) {
				mqtt.setPassword(getPasswordAuth());
			}

			result.sampleStart();
			connection = mqtt.futureConnection();
			Future<Void> f1 = connection.connect();
			f1.await(Integer.parseInt(getConnTimeout()), TimeUnit.SECONDS);

			Topic[] topics = { new Topic("topic_" + clientId, QoS.AT_LEAST_ONCE) };
			connection.subscribe(topics);

			result.sampleEnd();
			result.setSuccessful(true);
			result.setResponseData("Successful.".getBytes());
			result.setResponseMessage(MessageFormat.format("Connection {0} connected successfully.", connection));
			result.setResponseCodeOK();
		} catch (Exception e) {
			logger.log(Priority.ERROR, e.getMessage(), e);
			result.sampleEnd();
			result.setSuccessful(false);
			result.setResponseMessage(MessageFormat.format("Connection {0} connected failed.", connection));
			result.setResponseData("Failed.".getBytes());
			result.setResponseCode("500");
		}
		return result;
	}

	@Override
	public void testEnded() {
		this.testEnded("local");
	}

	@Override
	public void testEnded(String arg0) {
		logger.info("in testEnded, isNonGUI=" + JMeter.isNonGUI() + ", sleepFlag=" + sleepFlag.get());
		this.interrupt = true;
		
		try {
			if (JMeter.isNonGUI()) {
				if(!sleepFlag.get()) {
					//The keepTime is saved as static variable, because keepTime variable in testEnded() 
					//returns with initial value.
					logger.info("The work has been done, will keep connection for " + keepTime + " sceconds.");
					TimeUnit.SECONDS.sleep(keepTime);	
					sleepFlag.set(true);
				}
				
				//The following code does not make sense, because the connection variable was already reset.
				//But it makes sense in threadFinished method.
				//The reason for not sleeping and then disconnecting in threadFinished is to avoid maintain too much of threads.
				//Otherwise each thread (for a thread-group) will be kept open before sleeping.
				/*if(this.connection != null) {
					this.connection.disconnect();
					logger.info(MessageFormat.format("The connection {0} disconneted successfully.", connection));	
				}*/
			}
		} catch(Exception ex) {
			logger.error(ex.getMessage(), ex);
		}
	}

	@Override
	public void testStarted() {
		this.testStarted("local");
	}

	@Override
	public void testStarted(String arg0) {
		sleepFlag.set(false);
		keepTime = Integer.parseInt(getConnKeepTime());
		logger.info("*** Keeptime is: "  + keepTime);
	}

	@Override
	public void threadFinished() {
		
	}

	private void sleepCurrentThreadAndDisconnect() {
		try {
			//If the connection is null or does not connect successfully, then not necessary to keep the connection.
			if(connection == null || (!connection.isConnected())) {
				if(connection == null) {
					logger.info("Connection is null.");
				} else if(!connection.isConnected()) {
					logger.info("Connection is created, but is not connected.");
				}
				return;
			}
			long start = System.currentTimeMillis();
			while ((System.currentTimeMillis() - start) <= TimeUnit.SECONDS.toMillis(keepTime)) {
				if (this.interrupt) {
					logger.info("interrupted flag is true, and stop the sleep.");
					break;
				}
				TimeUnit.SECONDS.sleep(1);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (connection != null) {
				connection.disconnect();
				logger.log(Priority.INFO, MessageFormat.format("The connection {0} disconneted successfully.", connection));
			}
		}
	}

	@Override
	public void threadStarted() {

	}

	@Override
	public boolean interrupt() {
		this.interrupt = true;
		if (!JMeter.isNonGUI()) {
			logger.info("In GUI mode, received the interrupt request from user.");
		}
		return true;
	}

	// Note: JMeter.isNonGUI() only valid when you specify "-n" option from command line, 
	// other cases (such as JMeter GUI, remote engine) all treat isNonGUI() as "false"
	// So, if you use remote engine, it's actually follow isNonGUI()=false path in this sampler code,
	// and you can control the test and stop it in JMeter GUI via say "Remote Stop All" button.
	//
	// You can also start remote agent with say "jmeter-server -DJMeter.NonGui=true",
	// in order to fool JMeter to treat isNonGUI() as "true".
	// However, the interrupt mechanism may not work well with remote engine when stop the remote test from JMeter GUI,
	// you may need to manually kill remote jmeter process to clean it up.
	// 
	
	/**
	 * In this listener, it can receive the interrupt event trigger by user.
	 */
	@Override
	public void sampleOccurred(SampleEvent event) {
		if (!JMeter.isNonGUI() && "MQTT Connection Sampler".equals(event.getResult().getSampleLabel() )) {
			logger.info("Created the sampler results, will sleep current thread for " + getConnKeepTime() + " sceconds");
			sleepCurrentThreadAndDisconnect();
		}
	}

	@Override
	public void sampleStarted(SampleEvent arg0) {
	}

	@Override
	public void sampleStopped(SampleEvent arg0) {
	}
}
