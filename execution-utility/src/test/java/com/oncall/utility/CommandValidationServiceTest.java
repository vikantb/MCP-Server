package com.oncall.utility;

import com.oncall.utility.service.CommandValidationService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CommandValidationServiceTest {

    private final CommandValidationService service = new CommandValidationService(
        "tail,grep,cat,ls,df,free,ps,cp,echo,bash,sh,dir,type,findstr,ipconfig"
    );

    @Test
    public void testValidDirectoryPaths() {
        // Absolute paths within /opt_apb/SPRINGBOOT/ are allowed
        assertTrue(service.isValid("cat /opt_apb/SPRINGBOOT/filebeat/filebeat.yml"));
        assertTrue(service.isValid("tail -n 50 /opt_apb/SPRINGBOOT/logs/app.log"));
        
        // Relative paths without .. are allowed
        assertTrue(service.isValid("cat filebeat.yml"));
        assertTrue(service.isValid("grep -i error logs/app.log"));
    }

    @Test
    public void testInvalidDirectoryPaths() {
        // Absolute paths outside allowed directories are blocked
        assertFalse(service.isValid("cat /etc/passwd"));
        assertFalse(service.isValid("tail -n 10 /var/log/messages"));
        
        // Directory traversal using .. is blocked
        assertFalse(service.isValid("cat ../../etc/passwd"));
        assertFalse(service.isValid("ls /opt_apb/SPRINGBOOT/../../etc"));
    }

    @Test
    public void testPipingAndRedirects() {
        // Piped commands are allowed if all commands are safe
        assertTrue(service.isValid("ps -eaf | grep ckyc"));
        assertTrue(service.isValid("cat /opt_apb/SPRINGBOOT/logs/app.log | grep ERROR"));

        // Redirections (> and >>) are allowed within the directory
        assertTrue(service.isValid("echo \"test\" > /opt_apb/SPRINGBOOT/filebeat.yml"));
        assertTrue(service.isValid("echo \"append\" >> /opt_apb/SPRINGBOOT/filebeat/filebeat.yml"));
        
        // If pipe contains unsafe commands, it's blocked
        assertFalse(service.isValid("ps -eaf | rm -rf /"));
        assertFalse(service.isValid("cat /etc/passwd | grep root")); // Invalid path in first command
    }

    @Test
    public void testBlockedOperations() {
        assertFalse(service.isValid("rm /opt_apb/SPRINGBOOT/filebeat.yml"));
        assertFalse(service.isValid("mv /opt_apb/SPRINGBOOT/a.txt /opt_apb/SPRINGBOOT/b.txt"));
        assertFalse(service.isValid("chmod +x /opt_apb/SPRINGBOOT/script.sh"));
    }

    @Test
    public void testCommandInjectionAttempts() {
        assertFalse(service.isValid("tail file.log; rm -rf /"));
        assertFalse(service.isValid("grep -i error log.txt && rm -rf /"));
        assertFalse(service.isValid("df -h & rm -rf /"));
        assertFalse(service.isValid("cat `whoami`"));
        assertFalse(service.isValid("cat $(whoami)"));
    }
}
