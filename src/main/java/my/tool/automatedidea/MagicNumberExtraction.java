package my.tool.automatedidea;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MagicNumberExtraction extends AnAction {

    private static final String MESSAGE_NUMBER_EXTRACTION = "Message Number Extraction";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        DataContext dataContext = e.getDataContext();
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);

        if (Optional.ofNullable(editor).map(Editor::getDocument).isEmpty()) {
            Messages.showMessageDialog("No editor document found!", MESSAGE_NUMBER_EXTRACTION, Messages.getInformationIcon());
            return;
        }

        List<DeclaredConstant> declaredConstants = new ArrayList<>();
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
                    || declaredConstants.stream().anyMatch(constant -> constant.surrounds(finalNumberStartPosition, finalNumberEndPosition))) {
                continue;
            }

            if (declaredConstants.stream().noneMatch(constant -> constant.getName().equals(constantName))) {
                modifiedContent = new StringBuilder(modifiedContent).insert(firstOpenCurlyBracketPosition + 1, "\n\t" + constantDeclaration).toString();

                Matcher constantDeclarationMatcher = Pattern.compile(constantDeclaration).matcher(modifiedContent);
                if (constantDeclarationMatcher.find()) {
                    declaredConstants.add(new DeclaredConstant(constantName, constantDeclarationMatcher.start(), constantDeclarationMatcher.end()));
                }
                numberStartPosition += constantDeclaration.length() + 2;
                numberEndPosition += constantDeclaration.length() + 2;
            }
            modifiedContent = new StringBuilder(modifiedContent).replace(numberStartPosition, numberEndPosition, constantName).toString();

            for (DeclaredConstant constant : declaredConstants) {
                Matcher matcher = Pattern.compile("private static final int %s = (\\d)+;".formatted(constant.name)).matcher(modifiedContent);
                if (matcher.find()) {
                    constant.startPosition = matcher.start();
                    constant.endPosition = matcher.end();
                }
            }

            numberMatcher = numberPattern.matcher(modifiedContent);
            resultOutputMessageBuilder.append(number).append(";");
        }

        editor.getDocument().setText(modifiedContent);
        String resultOutputMessage = resultOutputMessageBuilder.isEmpty() ? "No magic number found!" : resultOutputMessageBuilder.insert(0, "Extracted: ").toString();
        Messages.showMessageDialog(resultOutputMessage, MESSAGE_NUMBER_EXTRACTION, Messages.getInformationIcon());
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

    class DeclaredConstant {
        private String name;
        private int startPosition;
        private int endPosition;

        private DeclaredConstant() {
            throw new UnsupportedOperationException();
        }

        DeclaredConstant(String name, int startPosition, int endPosition) {
            this.name = name;
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
