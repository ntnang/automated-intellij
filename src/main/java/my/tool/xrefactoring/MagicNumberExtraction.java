package my.tool.xrefactoring;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MagicNumberExtraction extends AnAction {

    private static final String MESSAGE_BOX_TITLE = "Message";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        DataContext dataContext = e.getDataContext();
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);

        if (Optional.ofNullable(editor).map(Editor::getDocument).isEmpty()) {
            Messages.showMessageDialog("No editor document found!", MESSAGE_BOX_TITLE, Messages.getInformationIcon());
            return;
        }

        List<Declaration> declarations = new ArrayList<>();
        List<Comment> comments = new ArrayList<>();

        Pattern numberPattern = Pattern.compile("\\d+");
        Matcher numberMatcher = numberPattern.matcher(editor.getDocument().getText());
        int firstOpenCurlyBracketPosition = editor.getDocument().getText().indexOf('{');
        StringBuilder resultOutputMessageBuilder = new StringBuilder();
        String modifiedContent = editor.getDocument().getText();

        while (numberMatcher.find()) {
            comments.clear();

            Matcher commentLineMatcher = Pattern.compile("\\/\\/.*").matcher(modifiedContent);
            while (commentLineMatcher.find()) {
                comments.add(new Comment(commentLineMatcher.start(), commentLineMatcher.end()));
            }

            Matcher commentBlockMatcher = Pattern.compile("\\/\\*[^\\/]*\\*\\/").matcher(modifiedContent);
            while (commentBlockMatcher.find()) {
                comments.add(new Comment(commentBlockMatcher.start(), commentBlockMatcher.end()));
            }

            Matcher declarationMatcher = Pattern.compile("\\w+\\s*=\\s*\\d+\\.?\\d*[f|l]?;").matcher(modifiedContent);
            while (declarationMatcher.find()) {
                List<String> declarationParts = Arrays.stream(declarationMatcher.group().trim().split("=")).collect(Collectors.toList());
                declarations.add(new Declaration(declarationParts.get(0), declarationParts.get(1), declarationMatcher.start(), declarationMatcher.end()));
            }

            String number = numberMatcher.group();
            String constantName = "MAGIC_NUMBER_" + number;
            String constantDeclaration = "private static final int %s = %s;".formatted(constantName, number);

            int numberStartPosition = numberMatcher.start();
            int numberEndPosition = numberMatcher.end();

            char prefixCharacter = modifiedContent.charAt(numberStartPosition - 1);
            char suffixCharacter = modifiedContent.charAt(numberEndPosition);

            int finalNumberStartPosition = numberStartPosition;
            int finalNumberEndPosition = numberEndPosition;
            if (isNotAMagicNumber(prefixCharacter, suffixCharacter)
                    || comments.stream().anyMatch(comment -> comment.surrounds(finalNumberStartPosition, finalNumberEndPosition))
                    || declarations.stream().anyMatch(constant -> constant.surrounds(finalNumberStartPosition, finalNumberEndPosition))) {
                continue;
            }

            if (declarations.stream().noneMatch(constant -> constant.getName().equals(constantName))) {
                modifiedContent = new StringBuilder(modifiedContent).insert(firstOpenCurlyBracketPosition + 1, "\n\t" + constantDeclaration).toString();

                Matcher constantDeclarationMatcher = Pattern.compile(constantDeclaration).matcher(modifiedContent);
                if (constantDeclarationMatcher.find()) {
                    declarations.add(new Declaration(constantName, number, constantDeclarationMatcher.start(), constantDeclarationMatcher.end()));
                }
                numberStartPosition += constantDeclaration.length() + 2;
                numberEndPosition += constantDeclaration.length() + 2;
            }
            modifiedContent = new StringBuilder(modifiedContent).replace(numberStartPosition, numberEndPosition, constantName).toString();

            for (Declaration constant : declarations) {
                Matcher matcher = Pattern.compile("private static final int %s = (\\d)+;".formatted(constant.name)).matcher(modifiedContent);
                if (matcher.find()) {
                    constant.startPosition = matcher.start();
                    constant.endPosition = matcher.end();
                }
            }

            numberMatcher = numberPattern.matcher(modifiedContent);

            if (resultOutputMessageBuilder.indexOf(number) == -1) {
                resultOutputMessageBuilder.append(number).append(";");
            }
        }

        String finalModifiedContent = modifiedContent;
        WriteCommandAction.runWriteCommandAction(e.getProject(), () -> {
            editor.getDocument().setText(finalModifiedContent);
        });
        String resultOutputMessage = resultOutputMessageBuilder.isEmpty() ? "No magic number found!" : resultOutputMessageBuilder.insert(0, "Extracted: ").toString();
        Messages.showMessageDialog(resultOutputMessage, MESSAGE_BOX_TITLE, Messages.getInformationIcon());

    }

    private static boolean isNotAMagicNumber(char prefixCharacter, char suffixCharacter) {
        return Character.isAlphabetic(prefixCharacter)
                || prefixCharacter == '_'
                || (prefixCharacter == '"' && suffixCharacter == '"')
                || (prefixCharacter == '\'' && suffixCharacter == '\'')
                || Character.isAlphabetic(suffixCharacter);
    }

    class Comment {

        private int startPosition;
        private int endPosition;

        private Comment() {
            throw new UnsupportedOperationException();
        }

        Comment(int startPosition, int endPosition) {
            this.startPosition = startPosition;
            this.endPosition = endPosition;
        }

        boolean surrounds(int startPosition, int endPosition) {
            return startPosition > this.startPosition && endPosition < this.endPosition;
        }
    }

    class Declaration {
        private String name;
        private String value;
        private int startPosition;
        private int endPosition;

        private Declaration() {
            throw new UnsupportedOperationException();
        }

        Declaration(String name, String number, int startPosition, int endPosition) {
            this.name = name;
            this.value = number;
            this.startPosition = startPosition;
            this.endPosition = endPosition;
        }

        boolean surrounds(int startPosition, int endPosition) {
            return startPosition > this.startPosition && endPosition < this.endPosition;
        }

        String getName() {
            return this.name;
        }

    }

}
