package org.xvm.asm;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.xvm.asm.constants.ConditionalConstant;
import org.xvm.asm.constants.TypeConstant;
import org.xvm.asm.constants.TypedefConstant;

import static org.xvm.util.Handy.readIndex;
import static org.xvm.util.Handy.writeMagnitude;


/**
 * An XVM Structure that represents a "typedef" statement, which acts as a way to name an arbitrary
 * type, by associating a named structure (this) with a type constant.
 */
public class TypedefStructure
        extends Component {
    // ----- constructors --------------------------------------------------------------------------

    /**
     * Construct a TypeDefStructure with the specified identity.
     *
     * @param xsParent   the XvmStructure that contains this structure
     * @param nFlags     the Component bit flags
     * @param constId    the constant that specifies the identity of the Typedef
     * @param condition  the optional condition for this TypeDefStructure
     */
    protected TypedefStructure(XvmStructure xsParent, int nFlags, TypedefConstant constId,
            ConditionalConstant condition) {
        super(xsParent, nFlags, constId, condition);
    }


    // ----- accessors -----------------------------------------------------------------------------

    @Override
    public TypedefConstant getIdentityConstant() {
        return (TypedefConstant) super.getIdentityConstant();
    }

    /**
     * @return the TypeConstant representing the data type of the typedef
     */
    public TypeConstant getType() {
        return m_type;
    }

    /**
     * Configure the typedef's type.
     *
     * @param type  the type constant that indicates the typedef's type
     */
    public void setType(TypeConstant type) {
        assert type != null;
        m_type = type;
    }

    /**
     * @return true iff this typedef declares one or more formal type parameters
     *         (e.g. {@code A} in {@code typedef X as Y<A>})
     */
    public boolean hasTypeParams() {
        return m_typeParamNames != null && m_typeParamNames.length > 0;
    }

    /**
     * @return the number of formal type parameters, or 0 if none
     */
    public int getTypeParamCount() {
        return m_typeParamNames == null ? 0 : m_typeParamNames.length;
    }

    /**
     * @return the name of the type parameter at the specified index
     */
    public String getTypeParamName(int i) {
        return m_typeParamNames[i];
    }

    /**
     * Configure the formal type parameter names declared on this typedef's alias.
     *
     * @param names  the formal names (e.g. {@code ["A", "B"]}); may be null or empty
     */
    public void setTypeParamNames(String[] names) {
        if (names == null || names.length == 0) {
            m_typeParamNames = null;
        } else {
            m_typeParamNames = names;
        }
    }

    /**
     * Create and register a generic type parameter under this typedef.
     */
    public void createTypeParameter(String sName) {
        ConstantPool           pool       = getConstantPool();
        org.xvm.asm.constants.PropertyConstant constParam = pool.ensurePropertyConstant(getIdentityConstant(), sName);
        int                    nFlags     = Format.PROPERTY.ordinal();
        PropertyStructure      struct     = new PropertyStructure(this, nFlags, constParam, null);
        struct.setType(pool.ensureClassTypeConstant(pool.clzType(), null, pool.typeObject()));
        struct.markAsGenericTypeParameter();
        addChild(struct);
    }


    // ----- XvmStructure methods ------------------------------------------------------------------

    @Override
    protected void disassemble(DataInput in)
    throws IOException {
        super.disassemble(in);

        m_type = getConstantPool().getConstant(readIndex(in), TypeConstant.class);
    }

    @Override
    protected void registerConstants(ConstantPool pool) {
        super.registerConstants(pool);

        m_type = pool.register(m_type);
    }

    @Override
    protected void assemble(DataOutput out)
    throws IOException {
        super.assemble(out);

        writeMagnitude(out, m_type.getPosition());
    }

    @Override
    public String getDescription() {
        return "type=" + m_type + ", " + super.getDescription();
    }


    // ----- fields --------------------------------------------------------------------------------

    /**
     * The actual type that the typedef represents.
     */
    private TypeConstant m_type;

    /**
     * The names of the formal type parameters declared on this typedef's alias
     * (e.g. {@code ["A", "B"]} for {@code typedef T as Y<A,B>}); null if no formals.
     */
    private String[] m_typeParamNames;
}
