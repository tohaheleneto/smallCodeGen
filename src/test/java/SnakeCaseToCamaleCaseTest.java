import org.assertj.core.api.Assertions;
import org.jetbrains.plugins.innerbuilder.InnerBuilderGenerator;
import org.junit.Assert;
import org.junit.Test;

public class SnakeCaseToCamaleCaseTest {
    @Test
    public void test1() {
        String s ="image_id";
        String s2 = InnerBuilderGenerator.snakeCaseToCamaleCase(s);

        Assertions.assertThat(s2).isEqualTo("imageId");
    }

    @Test
    public void test2() {
        String s ="image_id_is_so_cool";
        String s2 = InnerBuilderGenerator.snakeCaseToCamaleCase(s);

        Assertions.assertThat(s2).isEqualTo("imageIdIsSoCool");
    }
}
