package org.fakereplace.manip;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;

import org.fakereplace.boot.Logger;

public class MethodInvokationManipulator
{

   Map<String, Set<VirtualToStaticData>> virtualToStaticMethod = new ConcurrentHashMap<String, Set<VirtualToStaticData>>();

   public synchronized void removeMethodRewrites(String className)
   {
      virtualToStaticMethod.remove(className);
   }

   /**
    * This can also be used to replace a static invokation with another static
    * invokation.
    * 
    * if newClass is null then the invokation is changed to point to a method on the current class
    * 
    * @param oldClass
    * @param newClass
    * @param methodName
    * @param methodDesc
    * @param newStaticMethodDesc
    */
   public void replaceVirtualMethodInvokationWithStatic(String oldClass, String newClass, String methodName, String methodDesc, String newStaticMethodDesc)
   {
      VirtualToStaticData data = new VirtualToStaticData(oldClass, newClass, methodName, methodDesc, newStaticMethodDesc, null);
      if (!virtualToStaticMethod.containsKey(oldClass))
      {
         virtualToStaticMethod.put(oldClass, new HashSet<VirtualToStaticData>());
      }
      virtualToStaticMethod.get(oldClass).add(data);
   }

   public void replaceVirtualMethodInvokationWithLocal(String oldClass, String methodName, String newMethodName, String methodDesc, String newStaticMethodDesc)
   {
      VirtualToStaticData data = new VirtualToStaticData(oldClass, null, methodName, methodDesc, newStaticMethodDesc, newMethodName);
      if (!virtualToStaticMethod.containsKey(oldClass))
      {
         virtualToStaticMethod.put(oldClass, new HashSet<VirtualToStaticData>());
      }
      virtualToStaticMethod.get(oldClass).add(data);
   }

   public void transformClass(ClassFile file)
   {

      Map<Integer, VirtualToStaticData> methodCallLocations = new HashMap<Integer, VirtualToStaticData>();
      Map<VirtualToStaticData, Integer> newClassPoolLocations = new HashMap<VirtualToStaticData, Integer>();
      Map<VirtualToStaticData, Integer> newCallLocations = new HashMap<VirtualToStaticData, Integer>();
      // first we need to scan the constant pool looking for
      // CONSTANT_method_info_ref structures
      ConstPool pool = file.getConstPool();
      for (int i = 1; i < pool.getSize(); ++i)
      {
         // we have a method call
         if (pool.getTag(i) == ConstPool.CONST_Methodref)
         {
            if (virtualToStaticMethod.containsKey(pool.getMethodrefClassName(i)))
            {
               for (VirtualToStaticData data : virtualToStaticMethod.get(pool.getMethodrefClassName(i)))
               {
                  if (pool.getMethodrefName(i).equals(data.methodName) && pool.getMethodrefType(i).equals(data.methodDesc))
                  {
                     // store the location in the const pool of the method ref
                     methodCallLocations.put(i, data);
                     // we have found a method call
                     // now lets replace it

                     // if we have not already stored a reference to our new
                     // method in the const pool
                     if (!newClassPoolLocations.containsKey(data))
                     {
                        // we have not added the new class reference or
                        // the new call location to the class pool yet
                        int newCpLoc;
                        if (data.newClass != null)
                        {
                           newCpLoc = pool.addClassInfo(data.newClass);
                        }
                        else
                        {

                           newCpLoc = pool.addClassInfo(file.getName());
                        }
                        newClassPoolLocations.put(data, newCpLoc);
                        int newNameAndType = pool.addNameAndTypeInfo(data.newMethodName, data.newStaticMethodDesc);
                        newCallLocations.put(data, pool.addMethodrefInfo(newCpLoc, newNameAndType));
                     }
                     break;
                  }

               }
            }
         }
      }

      // this means we found an instance of the call, now we have to iterate
      // through the methods and replace instances of the call
      if (!newClassPoolLocations.isEmpty())
      {
         List<MethodInfo> methods = file.getMethods();
         for (MethodInfo m : methods)
         {
            try
            {
               // ignore abstract methods
               if (m.getCodeAttribute() == null)
               {
                  continue;
               }
               CodeIterator it = m.getCodeAttribute().iterator();
               while (it.hasNext())
               {
                  // loop through the bytecode
                  int index = it.next();
                  int op = it.byteAt(index);
                  // if the bytecode is a method invocation
                  if (op == CodeIterator.INVOKEVIRTUAL || op == CodeIterator.INVOKESTATIC)
                  {
                     int val = it.s16bitAt(index + 1);
                     // if the method call is one of the methods we are
                     // replacing
                     if (methodCallLocations.containsKey(val))
                     {
                        VirtualToStaticData data = methodCallLocations.get(val);
                        // change the call to an invokestatic
                        it.writeByte(CodeIterator.INVOKESTATIC, index);
                        // change the method that is being called
                        it.write16bit(newCallLocations.get(data), index + 1);
                     }
                  }
               }
               m.getCodeAttribute().computeMaxStack();
            }
            catch (Exception e)
            {
               Logger.log(this, "Bad byte code transforming " + file.getName());
               e.printStackTrace();
            }
         }
      }
   }

   static private class VirtualToStaticData
   {
      final String oldClass;
      final String newClass;
      final String methodName;
      final String newMethodName;
      final String methodDesc;
      final String newStaticMethodDesc;

      public VirtualToStaticData(String oldClass, String newClass, String methodName, String methodDesc, String newStaticMethodDesc, String newMethodName)
      {
         this.oldClass = oldClass;
         this.newClass = newClass;
         this.methodName = methodName;
         if (newMethodName == null)
         {
            this.newMethodName = methodName;
         }
         else
         {
            this.newMethodName = newMethodName;
         }
         this.methodDesc = methodDesc;
         this.newStaticMethodDesc = newStaticMethodDesc;
      }

      public String toString()
      {
         StringBuilder sb = new StringBuilder();
         sb.append(oldClass);
         sb.append(" ");
         sb.append(newClass);
         sb.append(" ");
         sb.append(methodName);
         sb.append(" ");
         sb.append(methodDesc);
         sb.append(" ");
         sb.append(newStaticMethodDesc);

         return sb.toString();
      }

      public boolean equals(Object o)
      {
         if (o.getClass().isAssignableFrom(VirtualToStaticData.class))
         {
            VirtualToStaticData i = (VirtualToStaticData) o;
            return oldClass.equals(i.oldClass) && newClass.equals(i.newClass) && methodName.equals(i.methodName) && methodDesc.equals(i.methodDesc) && newStaticMethodDesc.equals(i.newStaticMethodDesc);
         }
         return false;
      }

      public int hashCode()
      {
         return toString().hashCode();
      }
   }

}
