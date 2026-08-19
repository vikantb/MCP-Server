
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

@Service
public class CommandService {

    private final String BASH_PATH="C:\\Program Files\\Git\\bin\\bash.exe";

    private String executeCommandInternal(String[] commandArgs, String workingDirectory) {
        try {
            ProcessBuilder pb = new ProcessBuilder(commandArgs);
            if (workingDirectory != null && !workingDirectory.trim().isEmpty()) {
                File dir = new File(workingDirectory);
                if (dir.exists() && dir.isDirectory()) {
                    pb.directory(dir);
                } else {
                    return "Error: Working directory does not exist or is not a directory: " + workingDirectory;
                }
            }
            // Merge stderr into stdout so we get all output in one stream
            pb.redirectErrorStream(true); 
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            return "Exit Code: " + exitCode + "\n\nOutput:\n" + output;
        } catch (Exception e) {
            return "Error executing command: " + e.getMessage();
        }
    }

    @Tool(description = "Executes a generic bash command on the local system")
    public String executeBashCommand(String command, String workingDirectory) {
        return executeCommandInternal(new String[]{BASH_PATH, "-c", command}, workingDirectory);
    }

    @Tool(description = "Executes a git pull in the specified repository")
    public String gitPull(String repositoryPath) {
        return executeCommandInternal(new String[]{BASH_PATH, "-c", "git pull"}, repositoryPath);
    }

    @Tool(description = "Stages all changes, commits them with a message, and pushes to the remote repository")
    public String gitPush(String repositoryPath, String commitMessage) {
        // Escape quotes in commit message
        String escapedMessage = commitMessage.replace("\"", "\\\"");
        String cmd = String.format("git add . && git commit -m \"%s\" && git push", escapedMessage);
        return executeCommandInternal(new String[]{BASH_PATH, "-c", cmd}, repositoryPath);
    }

    @Tool(description = "Executes a maven build (mvn clean install) in the specified project directory")
    public String mavenBuild(String projectPath, boolean skipTests) {
        String cmd = skipTests ? "mvn clean install -DskipTests" : "mvn clean install";
        return executeCommandInternal(new String[]{BASH_PATH, "-c", cmd}, projectPath);
    }
}
