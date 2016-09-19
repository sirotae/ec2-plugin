package hudson.plugins.ec2.win;

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.win.winrm.WindowsProcess;
import hudson.slaves.ComputerLauncher;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;

public class EC2WindowsLauncher extends EC2ComputerLauncher {
    private static final String SLAVE_JAR = "slave.jar";   
    
    final long sleepBetweenAttemps = TimeUnit.SECONDS.toMillis(10);

    @Override
    protected void launch(EC2Computer computer, TaskListener listener, Instance inst) throws IOException,
            AmazonClientException, InterruptedException {
        final PrintStream logger = listener.getLogger();
        final WinConnection connection = connectToWinRM(computer, logger);

        try {
            String initScript = computer.getNode().initScript;
            String tmpDir = (computer.getNode().tmpDir != null && !computer.getNode().tmpDir.equals("") ? computer.getNode().tmpDir
                    : "C:\\Windows\\Temp\\");

            logger.println("Creating tmp directory if it does not exist");
            connection.execute("if not exist " + tmpDir + " mkdir " + tmpDir);

            if (initScript != null && initScript.trim().length() > 0 && !connection.exists(tmpDir + ".jenkins-init")) {
                logger.println("Executing init script");   
                writeFile(connection, tmpDir + "init.bat", initScript.getBytes("utf-8"));   
                
                executeAndWaitTermination (connection, "cmd /c " + tmpDir + "init.bat", logger, "init script failed");
                writeFile(connection, tmpDir + ".jenkins-init", "init ran".getBytes(StandardCharsets.UTF_8));
                
                logger.println("init script ran successfully");
            }

            writeFile(connection, tmpDir + SLAVE_JAR, Jenkins.getInstance().getJnlpJars(SLAVE_JAR).readFully());
            logger.println("slave.jar sent remotely");                

            String slaveLaunchBatch = tmpDir + "slave.bat"; 
            String slaveLaunchBatchScript = "java -jar " + tmpDir + "slave.jar"+
                " -jnlpUrl " + BatUtils.escapePercent("\""+Jenkins.getInstance().getRootUrl()+computer.getUrl()+"slave-agent.jnlp\"") + 
                " -secret " + computer.getJnlpMac();
            String interactiveLaunchScript = "powershell -command \"$slaveAgentBatch = '" + slaveLaunchBatch + 
                    "'; $JobUser = '" + computer.getNode().remoteAdmin + 
                    "'; $jobName = 'JenkinsDynamicAgent'; $pr = New-ScheduledTaskPrincipal -RunLevel Highest -Userid $jobUser -LogonType Interactive; $st = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::FromDays(99)) -RestartCount 99 -RestartInterval ([TimeSpan]::FromSeconds(60)) -StartWhenAvailable; $tr = New-ScheduledTaskTrigger -AtLogOn -User $jobUser; $a = New-ScheduledTaskAction -Execute $slaveAgentBatch; Register-ScheduledTask -TaskName $jobName -Action $a -Trigger $tr -Settings $st -Principal $pr -Force; Start-ScheduledTask -TaskName $jobName;\"";
            
            logger.println("Jenkins jnlp slave batch: " + slaveLaunchBatchScript); 
            logger.println("Executing script for starting jenkins batch in interactive session: " + interactiveLaunchScript); 
 
            writeFile(connection, slaveLaunchBatch, slaveLaunchBatchScript.getBytes("utf-8"));
            executeAndWaitTermination (connection, "mofcomp C:\\Windows\\System32\\wbem\\SchedProv.mof", logger, "mofcomp script failed");           
            startExecuting(connection, interactiveLaunchScript, logger, 86400);

            logger.println("scripts ran successfully.");
        } catch (IOException ioe) {
            logger.println("Ouch:");
            ioe.printStackTrace(logger);
        } finally {
            connection.close();
        }
    }
    
    private void writeFile(WinConnection connection, String fullFileName, byte[] bytes) throws IOException {
        OutputStream init = connection.putFile(fullFileName);
        init.write(bytes);
    }
    
    private void executeAndWaitTermination (WinConnection connection, String command, PrintStream logger, String exceptionMessage) throws IOException {
        WindowsProcess initProcess = connection.execute(command);
        IOUtils.copy(initProcess.getStdout(), logger);

        int exitStatus = initProcess.waitFor();
        if (exitStatus!=0)
            throw new IOException (exceptionMessage + " exit code " + exitStatus);
    }
    
    private WindowsProcess startExecuting (WinConnection connection, String command, PrintStream logger, int timeout) throws IOException {
        WindowsProcess initProcess = connection.execute(command, timeout);
        IOUtils.copy(initProcess.getStdout(), logger);
        return initProcess;
    }

    private WinConnection connectToWinRM(EC2Computer computer, PrintStream logger) throws AmazonClientException,
            InterruptedException {
        final long minTimeout = 3000;
        long timeout = computer.getNode().getLaunchTimeoutInMillis(); // timeout is less than 0 when jenkins is booting up.
        if (timeout < minTimeout) {
            timeout = minTimeout;
        }
        final long startTime = System.currentTimeMillis();

        logger.println(computer.getNode().getDisplayName() + " booted at " + computer.getNode().getCreatedTime());
        boolean alreadyBooted = (startTime - computer.getNode().getCreatedTime()) > TimeUnit.MINUTES.toMillis(3);
        while (true) {
            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if (waitTime > timeout) {
                    throw new AmazonClientException("Timed out after " + (waitTime / 1000)
                            + " seconds of waiting for winrm to be connected");
                }
                Instance instance = computer.updateInstanceDescription();
                String ip, host;

                if (computer.getNode().usePrivateDnsName) {
                    host = instance.getPrivateDnsName();
                    ip = instance.getPrivateIpAddress(); // SmbFile doesn't
                                                         // quite work with
                                                         // hostnames
                } else {
                    host = instance.getPublicDnsName();
                    if (host == null || host.equals("")) {
                        host = instance.getPrivateDnsName();
                        ip = instance.getPrivateIpAddress(); // SmbFile doesn't
                                                             // quite work with
                                                             // hostnames
                    } else {
                        host = instance.getPublicDnsName();
                        ip = instance.getPublicIpAddress(); // SmbFile doesn't
                                                            // quite work with
                                                            // hostnames
                    }
                }

                if ("0.0.0.0".equals(host)) {
                    logger.println("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                logger.println("Connecting to " + host + "(" + ip + ") with WinRM as " + computer.getNode().remoteAdmin);

                WinConnection connection = new WinConnection(ip, computer.getNode().remoteAdmin, computer.getNode().getAdminPassword().getPlainText());

                connection.setUseHTTPS(computer.getNode().isUseHTTPS());
                if (!connection.ping()) {
                    logger.println("Waiting for WinRM to come up. Sleeping 10s.");
                    Thread.sleep(sleepBetweenAttemps);
                    continue;
                }

                if (!alreadyBooted || computer.getNode().stopOnTerminate) {
                    logger.println("WinRM service responded. Waiting for WinRM service to stabilize on "
                            + computer.getNode().getDisplayName());
                    Thread.sleep(computer.getNode().getBootDelay());
                    alreadyBooted = true;
                    logger.println("WinRM should now be ok on " + computer.getNode().getDisplayName());
                    if (!connection.ping()) {
                        logger.println("WinRM not yet up. Sleeping 10s.");
                        Thread.sleep(sleepBetweenAttemps);
                        continue;
                    }
                }

                logger.println("Connected with WinRM.");
                return connection; // successfully connected
            } catch (IOException e) {
                logger.println("Waiting for WinRM to come up. Sleeping 10s.");
                Thread.sleep(sleepBetweenAttemps);
            }
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
