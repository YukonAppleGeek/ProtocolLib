/*
 *  ProtocolLib - Bukkit server library that allows access to the Minecraft protocol.
 *  Copyright (C) 2012 Kristian S. Stangeland
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the 
 *  GNU General Public License as published by the Free Software Foundation; either version 2 of 
 *  the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with this program; 
 *  if not, write to the Free Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 
 *  02111-1307 USA
 */

package com.comphenix.protocol.wrappers.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import com.comphenix.protocol.reflect.FieldAccessException;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.BukkitConverters;

/**
 * Factory methods for creating NBT elements, lists and compounds.
 * 
 * @author Kristian
 */
public class NbtFactory {
	// Used to create the underlying tag
	private static Method methodCreateTag;
	
	// Used to read and write NBT
	private static Method methodWrite;
	private static Method methodLoad;
	
	// Item stack trickery
	private static StructureModifier<Object> itemStackModifier;
	
	/**
	 * Attempt to cast this wrapper as a compund.
	 * @return This instance as a compound.
	 * @throws UnsupportedOperationException If this is not a compound.
	 */
	public static NbtCompound asCompound(NbtWrapper<?> wrapper) {
		if (wrapper instanceof NbtCompound)
			return (NbtCompound) wrapper;
		else if (wrapper != null)
			throw new UnsupportedOperationException(
					"Cannot cast a " + wrapper.getClass() + "( " + wrapper.getType() + ") to TAG_COMPUND.");
		else
			throw new IllegalArgumentException("Wrapper cannot be NULL.");
	}
	
	/**
	 * Attempt to cast this wrapper as a list.
	 * @return This instance as a list.
	 * @throws UnsupportedOperationException If this is not a list.
	 */
	public static NbtList<?> asList(NbtWrapper<?> wrapper) {
		if (wrapper instanceof NbtList)
			return (NbtList<?>) wrapper;
		else if (wrapper != null)
			throw new UnsupportedOperationException(
					"Cannot cast a " + wrapper.getClass() + "( " + wrapper.getType() + ") to TAG_LIST.");
		else
			throw new IllegalArgumentException("Wrapper cannot be NULL.");
	}
	
	/**
	 * Get a NBT wrapper from a NBT base.
	 * @param base - the base class.
	 * @return A NBT wrapper.
	 */
	@SuppressWarnings("unchecked")
	public static <T> NbtWrapper<T> fromBase(NbtBase<T> base) {
		if (base instanceof WrappedElement) {
			return (WrappedElement<T>) base;
		} else if (base instanceof WrappedCompound) {
			return (NbtWrapper<T>) base;
		} else if (base instanceof WrappedList) {
			return (NbtWrapper<T>) base;
		} else {
			if (base.getType() == NbtType.TAG_COMPOUND) {
				// Load into a NBT-backed wrapper
				WrappedCompound copy = WrappedCompound.fromName(base.getName());
				T value = base.getValue();
				
				copy.setValue((Map<String, NbtBase<?>>) value);
				return (NbtWrapper<T>) copy;
			
			} else if (base.getType() == NbtType.TAG_LIST) {
				// As above
				WrappedList<T> copy = WrappedList.fromName(base.getName());
				
				copy.setValue((List<NbtBase<T>>) base.getValue());
				return (NbtWrapper<T>) copy;
				
			} else {
				// Copy directly
				NbtWrapper<T> copy = ofType(base.getType(), base.getName());
				
				copy.setValue(base.getValue());
				return copy;
			}
		}
	}
	
	/**
	 * Construct a wrapper for an NBT tag stored (in memory) in an item stack.
	 * <p>
	 * The item stack must be a wrapper for a CraftItemStack. Use 
	 * {@link MinecraftReflection#getBukkitItemStack(ItemStack)} if not.
	 * @param stack - the item stack.
	 * @return A wrapper for its NBT tag.
	 */
	public static NbtWrapper<?> fromItemStack(ItemStack stack) {
		if (!MinecraftReflection.isCraftItemStack(stack))
			throw new IllegalArgumentException("Stack must be a CraftItemStack.");
		
		Object nmsStack = MinecraftReflection.getMinecraftItemStack(stack);
		
		if (itemStackModifier == null) {
			itemStackModifier = new StructureModifier<Object>(nmsStack.getClass(), Object.class, false);
		}
		
		// Use the first and best NBT tag
		StructureModifier<NbtWrapper<?>> modifier = itemStackModifier.
				withTarget(nmsStack).
				withType(MinecraftReflection.getNBTBaseClass(), BukkitConverters.getNbtConverter());
		NbtWrapper<?> result = modifier.read(0);
		
		// Create the tag if it doesn't exist
		if (result == null) {
			result = NbtFactory.ofCompound("tag");
			modifier.write(0, result);
		}
		return result;
	}
	
	/**
	 * Initialize a NBT wrapper.
	 * @param handle - the underlying net.minecraft.server object to wrap.
	 * @return A NBT wrapper.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T> NbtWrapper<T> fromNMS(Object handle) {
		WrappedElement<T> partial = new WrappedElement<T>(handle);
		
		// See if this is actually a compound tag
		if (partial.getType() == NbtType.TAG_COMPOUND)
			return (NbtWrapper<T>) new WrappedCompound(handle);
		else if (partial.getType() == NbtType.TAG_LIST)
			return new WrappedList(handle);
		else
			return partial;
	}
	
	/**
	 * Write the content of a wrapped NBT tag to a stream.
	 * @param value - the NBT tag to write.
	 * @param destination - the destination stream.
	 */
	public static <TType> void toStream(NbtWrapper<TType> value, DataOutput destination) {
		if (methodWrite == null) {
			Class<?> base = MinecraftReflection.getNBTBaseClass();
			
			// Use the base class
			methodWrite = FuzzyReflection.fromClass(base).
					getMethodByParameters("writeNBT", base, DataOutput.class);
		}
		
		try {
			methodWrite.invoke(null, fromBase(value).getHandle(), destination);
		} catch (Exception e) {
			throw new FieldAccessException("Unable to write NBT " + value, e);
		}
	}

	/**
	 * Load an NBT tag from a stream.
	 * @param source - the input stream.
	 * @return An NBT tag.
	 */
	public static NbtWrapper<?> fromStream(DataInput source) {
		if (methodLoad == null) {
			Class<?> base = MinecraftReflection.getNBTBaseClass();
			
			// Use the base class
			methodLoad = FuzzyReflection.fromClass(base).
					getMethodByParameters("load", base, new Class<?>[] { DataInput.class });
		}
		
		try {
			return fromNMS(methodLoad.invoke(null, source));
		} catch (Exception e) {
			throw new FieldAccessException("Unable to read NBT from " + source, e);
		}
	}
	
	/**
	 * Constructs a NBT tag of type string.
	 * @param name - name of the tag.
	 * @param value - value of the tag. 
	 * @return The constructed NBT tag.
	 */
	public static NbtWrapper<String> of(String name, String value) {
		return ofType(NbtType.TAG_STRING, name, value);
	}
	
	/**
	 * Constructs a NBT tag of type byte.
	 * @param name - name of the tag.
	 * @param value - value of the tag. 
	 * @return The constructed NBT tag.
	 */
	public static NbtWrapper<Byte> of(String name, byte value) {
		return ofType(NbtType.TAG_BYTE, name, value);
	}
	
	/**
	 * Constructs a NBT tag of type short.
	 * @param name - name of the tag.
	 * @param value - value of the tag. 
	 * @return The constructed NBT tag.
	 */
	public static NbtWrapper<Short> of(String name, short value) {
		return ofType(NbtType.TAG_SHORT, name, value);
	}
	
	/**
	 * Constructs a NBT tag of type int.
	 * @param name - name of the tag.
	 * @param value - value of the tag. 
	 * @return The constructed NBT tag.
	 */
	public static NbtWrapper<Integer> of(String name, int value) {
		return ofType(NbtType.TAG_INT, name, value);
	}
	
	/**
	 * Constructs a NBT tag of type long.
	 * @param name - name of the tag.
	 * @param value - value of the tag. 
	 * @return The constructed NBT tag.
	 */
	public static NbtWrapper<Long> of(String name, long value) {
		return ofType(NbtType.TAG_LONG, name, value);
	}
	
	/**
	 * Constructs a NBT tag of type float.
	 * @param name - name of the tag.
	 * @param value - value of the tag. 
	 * @return The constructed NBT tag.
	 */
	public static NbtWrapper<Float> of(String name, float value) {
		return ofType(NbtType.TAG_FLOAT, name, value);
	}
	
	/**
	 * Constructs a NBT tag of type double.
	 * @param name - name of the tag.
	 * @param value - value of the tag. 
	 * @return The constructed NBT tag.
	 */
	public static NbtWrapper<Double> of(String name, double value) {
		return ofType(NbtType.TAG_DOUBlE, name, value);
	}
	
	/**
	 * Constructs a NBT tag of type byte array.
	 * @param name - name of the tag.
	 * @param value - value of the tag. 
	 * @return The constructed NBT tag.
	 */
	public static NbtWrapper<byte[]> of(String name, byte[] value) {
		return ofType(NbtType.TAG_BYTE_ARRAY, name, value);
	}
	
	/**
	 * Constructs a NBT tag of type int array.
	 * @param name - name of the tag.
	 * @param value - value of the tag. 
	 * @return The constructed NBT tag.
	 */
	public static NbtWrapper<int[]> of(String name, int[] value) {
		return ofType(NbtType.TAG_INT_ARRAY, name, value);
	}
	
	/**
	 * Construct a new NBT compound wrapper initialized with a given list of NBT values.
	 * @param name - the name of the compound wrapper. 
	 * @param list - the list of elements to add.
	 * @return The new wrapped NBT compound.
	 */
	public static NbtCompound ofCompound(String name, Collection<? extends NbtBase<?>> list) {
		return WrappedCompound.fromList(name, list);
	}
	
	/**
	 * Construct a new NBT compound wrapper.
	 * @param name - the name of the compound wrapper. 
	 * @return The new wrapped NBT compound.
	 */
	public static WrappedCompound ofCompound(String name) {
		return WrappedCompound.fromName(name);
	}
	
	/**
	 * Construct a NBT list of out an array of values.
	 * @param name - name of this list.
	 * @param elements - elements to add.
	 * @return The new filled NBT list.
	 */
	public static <T> NbtList<T> ofList(String name, T... elements) {
		return WrappedList.fromArray(name, elements);
	}
	
	/**
	 * Create a new NBT wrapper from a given type.
	 * @param type - the NBT type.
	 * @param name - the name of the NBT tag.
	 * @return The new wrapped NBT tag.
	 * @throws FieldAccessException If we're unable to create the underlying tag.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T> NbtWrapper<T> ofType(NbtType type, String name) {
		if (type == null)
			throw new IllegalArgumentException("type cannot be NULL.");
		if (type == NbtType.TAG_END)
			throw new IllegalArgumentException("Cannot create a TAG_END.");
		
		if (methodCreateTag == null) {
			Class<?> base = MinecraftReflection.getNBTBaseClass();
			
			// Use the base class
			methodCreateTag = FuzzyReflection.fromClass(base).
				getMethodByParameters("createTag", base, new Class<?>[] { byte.class, String.class });
		}
		
		try {
			Object handle = methodCreateTag.invoke(null, (byte) type.getRawID(), name);
			
			if (type == NbtType.TAG_COMPOUND)
				return (NbtWrapper<T>) new WrappedCompound(handle);
			else if (type == NbtType.TAG_LIST)
				return (NbtWrapper<T>) new WrappedList(handle);
			else
				return new WrappedElement<T>(handle);
			
		} catch (Exception e) {
			// Inform the caller
			throw new FieldAccessException(
					String.format("Cannot create NBT element %s (type: %s)", name, type),
					e);
		}
	}
	
	/**
	 * Create a new NBT wrapper from a given type.
	 * @param type - the NBT type.
	 * @param name - the name of the NBT tag.
	 * @param value - the value of the new tag.
	 * @return The new wrapped NBT tag.
	 * @throws FieldAccessException If we're unable to create the underlying tag.
	 */
	public static <T> NbtWrapper<T> ofType(NbtType type, String name, T value) {
		NbtWrapper<T> created = ofType(type, name);
		
		// Update the value
		created.setValue(value);
		return created;
	}
	
	/**
	 * Create a new NBT wrapper from a given type.
	 * @param type - type of the NBT value.
	 * @param name - the name of the NBT tag.
	 * @param value - the value of the new tag.
	 * @return The new wrapped NBT tag.
	 * @throws FieldAccessException If we're unable to create the underlying tag.
	 * @throws IllegalArgumentException If the given class type is not valid NBT.
	 */
	public static <T> NbtWrapper<T> ofType(Class<?> type, String name, T value) {
		return ofType(NbtType.getTypeFromClass(type), name, value);
	}
}