package com.oncall.utility.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CommandValidationService {

    private final Set<String> allowedBinaries;

    public CommandValidationService(@Value("${allowed.commands:tail,grep,cat,ls,df,free,ps,cp,echo,bash,sh,dir,type,findstr,ipconfig}") String allowedCmds) {
        this.allowedBinaries = Arrays.stream(allowedCmds.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    public boolean isValid(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        // Split by pipes to validate each command in the pipeline
        String[] subCommands = command.split("\\|");
        for (String sub : subCommands) {
            if (!isValidSubCommand(sub)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidSubCommand(String sub) {
        String trimmed = sub.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        // 1. Prevent dangerous chaining/injection control characters (excluding > and >>)
        // Block: ;, &, `, $, \n, \r, &&, ||
        String[] forbiddenPatterns = { ";", "&", "`", "$", "\n", "\r", "&&", "||" };
        for (String pattern : forbiddenPatterns) {
            if (trimmed.contains(pattern)) {
                return false;
            }
        }

        // 2. Tokenize and validate command parts
        // Note: we clean redirect symbols out of tokens for path checking
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length == 0) {
            return false;
        }

        // Extract and validate binary (first token)
        // If it starts with a path (like /opt_apb/SPRINGBOOT/filebeat/start.sh), we extract the binary name
        String binaryToken = tokens[0];
        if (!isPathAllowed(binaryToken)) {
            return false;
        }

        String binaryName = getBinaryName(binaryToken);
        if (!allowedBinaries.contains(binaryName.toLowerCase()) && !binaryToken.endsWith(".sh")) {
            return false;
        }

        // 3. Validate all other tokens (paths, arguments) to ensure directory constraints
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            
            // Clean redirection symbols
            if (token.equals(">") || token.equals(">>")) {
                continue;
            }
            
            if (!isPathAllowed(token)) {
                return false;
            }
        }

        return true;
    }

    private boolean isPathAllowed(String token) {
        // Prevent directory traversal
        if (token.contains("..")) {
            return false;
        }

        // If it starts with / or a Windows drive letter, treat it as an absolute path
        if (token.startsWith("/") || token.matches("^[a-zA-Z]:/.*") || token.matches("^[a-zA-Z]:\\\\.*")) {
            String normalized = token.replace('\\', '/').toLowerCase();
            boolean isLinuxAllowed = normalized.startsWith("/opt_apb/springboot/");
            
            // Also allow the workspace/STS directory if testing on Windows
            String os = System.getProperty("os.name").toLowerCase();
            boolean isWindowsAllowed = os.contains("win") && 
                    (normalized.startsWith("d:/documents/sts/") || normalized.startsWith("c:/documents/sts/"));
                    
            return isLinuxAllowed || isWindowsAllowed;
        }

        return true; // Relative paths without ".." are allowed
    }

    private String getBinaryName(String path) {
        int lastSlash = path.replace('\\', '/').lastIndexOf('/');
        if (lastSlash != -1 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }
}
