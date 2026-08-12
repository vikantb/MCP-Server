package com.oncall.utility.controller;

import com.oncall.utility.service.CommandValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
public class CommandController {

    private final CommandValidationService validationService;

    public CommandController(CommandValidationService validationService) {
        this.validationService = validationService;
    }

    @PostMapping(value = "/api/execute", consumes = "text/plain", produces = "text/plain")
    public ResponseEntity<String> executeCommand(@RequestBody String command) {
        if (!validationService.isValid(command)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error: Command rejected due to sanitization rules. Commands must run strictly in /opt_apb/SPRINGBOOT/ (no directory traversal via ..), use allowed binaries (tail, grep, cat, ls, df, free, ps, cp, echo, bash, sh), and avoid dangerous chaining. Piping via | is supported.");
        }

        try {
            // Automatic backup logic for file updates via redirection (> or >>)
            if (command.contains(">")) {
                int redirectIdx = command.indexOf(">");
                int offset = (redirectIdx < command.length() - 1 && command.charAt(redirectIdx + 1) == '>') ? 2 : 1;
                String afterRedirect = command.substring(redirectIdx + offset).trim();
                String[] words = afterRedirect.split("\\s+");
                if (words.length > 0) {
                    String targetFile = words[0];
                    java.io.File file = new java.io.File(targetFile);
                    if (file.exists() && file.isFile()) {
                        java.io.File backup = new java.io.File(targetFile + ".bak");
                        java.nio.file.Files.copy(
                            file.toPath(),
                            backup.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        );
                    }
                }
            }

            List<String> processArgs = new ArrayList<>();
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                processArgs.add("cmd.exe");
                processArgs.add("/c");
                processArgs.add(command);
            } else {
                processArgs.add("bash");
                processArgs.add("-c");
                processArgs.add(command);
            }

            ProcessBuilder pb = new ProcessBuilder(processArgs);
            Process process = pb.start();

            String output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            String error = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                return ResponseEntity.ok(output);
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Command failed with status " + exitCode + "\nOutput:\n" + output + "\nErrors:\n" + error);
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error executing command: " + e.getMessage());
        }
    }
}
