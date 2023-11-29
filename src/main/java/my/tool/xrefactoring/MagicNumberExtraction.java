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
    private static final List<Class<?>> declaredTypes = List.of(int.class, long.class, float.class, double.class, Integer.class, Long.class, Float.class, Double.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        DataContext dataContext = e.getDataContext();
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);

        if (Optional.ofNullable(editor).map(Editor::getDocument).isEmpty()) {
            Messages.showMessageDialog("No editor document found!", MESSAGE_BOX_TITLE, Messages.getInformationIcon());
            return;
        }

        Set<Declaration> declarations = new HashSet<>();
        List<Comment> comments = new ArrayList<>();

        Pattern numberPattern = Pattern.compile("\\d+\\.?\\d*[d|f|l]?");
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

            String allTypes = declaredTypes.stream().map(Class::getSimpleName).map("(%s)"::formatted).collect(Collectors.joining("|"));
            Matcher declarationMatcher = Pattern.compile("(%s)\\s+\\w+\\s*=\\s*\\d+\\.?\\d*[d|f|l]?;".formatted(allTypes)).matcher(modifiedContent);
            while (declarationMatcher.find()) {
                String declaration = declarationMatcher.group();
                int firstSpacePosition = declaration.indexOf(' ');
                String declaredType = declaration.substring(0, firstSpacePosition);
                List<String> declarationParts = Arrays.stream(declaration.substring(firstSpacePosition + 1).replaceAll("\\s|\\;", "").split("=")).toList();
                declarations.add(new Declaration(declarationParts.get(0), declarationParts.get(1), declaredType, declarationMatcher.start(), declarationMatcher.end()));
            }

            String number = numberMatcher.group();
            String constantName = "MAGIC_NUMBER_" + number.replace('.', '_');
            String constantType = getConstantType(number);
            String constantDeclaration = "private static final %s %s = %s;".formatted(constantType, constantName, number);

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
                    declarations.add(new Declaration(constantName, number, constantType, constantDeclarationMatcher.start(), constantDeclarationMatcher.end()));
                }
                numberStartPosition += constantDeclaration.length() + 2;
                numberEndPosition += constantDeclaration.length() + 2;
            }
            modifiedContent = new StringBuilder(modifiedContent).replace(numberStartPosition, numberEndPosition, constantName).toString();

            for (Declaration constant : declarations) {
                Matcher matcher = Pattern.compile("%s\\s+%s\\s*=\\s*%s;".formatted(constant.type, constant.name, constant.value)).matcher(modifiedContent);
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
        WriteCommandAction.runWriteCommandAction(e.getProject(), () -> editor.getDocument().setText(finalModifiedContent));
        String resultOutputMessage = resultOutputMessageBuilder.isEmpty() ? "No magic number found!" : resultOutputMessageBuilder.insert(0, "Extracted: ").toString();
        Messages.showMessageDialog(resultOutputMessage, MESSAGE_BOX_TITLE, Messages.getInformationIcon());

    }

    @NotNull
    private static String getConstantType(String number) {
        return switch (number.charAt(number.length() - 1)) {
            case 'd' -> "double";
            case 'f' -> "float";
            case 'l' -> "long";
            default -> number.contains(".") ? "double" : "int";
        };
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
        private String type;
        private int startPosition;
        private int endPosition;

        Declaration(String name, String number, String type, int startPosition, int endPosition) {
            this.name = name;
            this.value = number;
            this.type = type;
            this.startPosition = startPosition;
            this.endPosition = endPosition;
        }

        boolean surrounds(int startPosition, int endPosition) {
            return startPosition > this.startPosition && endPosition < this.endPosition;
        }

        String getName() {
            return this.name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Declaration that = (Declaration) o;
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }

}
