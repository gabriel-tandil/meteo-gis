
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Daemonio
{
	private static final String	MENOR_A						= "Menor a ";
	private static final String	MENOR_A_100_METROS			= "Menor a 100 mts";
	private static final String	FORMATO_FECHA				= "dd-MM-yyyyHH:mm";
	private static final String	PAGINA_VALORES_ESTACION		= "http://www.smn.gov.ar/?mod=dpd&id=21&e=";
	private static final int	HORAS_ENTRE_CONSULTA_EST	= 3;
	private static final String	DRIVER_DE_CONEXION			= "org.postgresql.Driver";
	// private static final String DRIVER_DE_CONEXION = "org.postgresql.Driver";
	private static final String	USUARIO_DE_CONEXION			= "postgres";
	private static final String	PASSWORD_DE_CONEXION		= "atlas";
	private static final String	CADENA_DE_CONEXION			= "jdbc:postgresql:geodb";
	// private static final String CADENA_DE_CONEXION = "jdbc:postgresql://localhost/booktown";
	private static final String	NO_HAY_VIENTO				= "Calma";
	private static final String	NO_HAY_SENSACION_TERMICA	= "<a href=?mod=dpd&id=19>No se calcula</a>";
	private static final String	SEPARADOR_CELDAS			= "</div></td><td align='center' class='font1'><div align='center'>";
	private static final String	SEPARADOR_FILAS				= "</tr><tr class='f1'>";
	private static final String	FIN_FILA					= "</div></td>";
	private static final String	INICIO_FILA					= "<td height='20' align='center' class='font1'><div align='center'>";
	private static final String	FIN_TABLA					= "</tr></table>";
	private static final String	INICIO_TABLA				= "<table WIDTH='580px' align='center' cellspacing='1'>    <tr class='f11'>      ";
	private static String conexionGET(String request) throws IOException
	{

		String responce = "";
		BufferedReader rd = null;
		try
		{
			URL url = new URL(request);

			URLConnection conn2 = url.openConnection();
			rd = new BufferedReader(new InputStreamReader(conn2.getInputStream()));

			String line;
			while ((line = rd.readLine()) != null)
			{
				responce += line;
			}
		}
		catch (IOException e)
		{
			System.out.println("Problema al ejecutar el request");
			throw e;
		}
		finally
		{
			if (rd != null)
			{
				try
				{
					rd.close();
				}
				catch (IOException ex)
				{
					System.out.println("Problema al cerrar el objeto lector");
					throw ex;
				}
			}
		}
		return responce;
	}

	public static void main(String[] args)
	{
		Connection conexion = null;
		Statement stmt = null;
		ResultSet rs = null;
		try
		{
			String driver = DRIVER_DE_CONEXION;
			String cadena = CADENA_DE_CONEXION;
			String usuario = USUARIO_DE_CONEXION;
			String password = PASSWORD_DE_CONEXION;
			if (args.length > 0)
			{
				if (args.length == 4)
				{

					driver = args[0];
					cadena = args[1];
					usuario = args[3];
					password = args[3];
				}
				else
				{
					System.out.println("Parámetros: <driver> <cadena_de_conexion> <usuario> <clave>");
				}
			}
			System.out.println("Iniciando Procesamiento: " + new Date());
			DriverManager.registerDriver((Driver) Class.forName(driver).newInstance());
			conexion = DriverManager.getConnection(cadena, usuario, password);
			conexion.setAutoCommit(true);

			stmt = conexion.createStatement();
			rs = stmt.executeQuery("SELECT id, nombre FROM estacion");

			while (rs.next())
			{
				ResultSet rs2 = null;
				try
				{
					int idEstacion = rs.getInt("id");
					System.out.println("\nEstación " + rs.getString("nombre") + " (" + idEstacion + ")");
					PreparedStatement stmt2 = conexion.prepareStatement("SELECT MAX(fecha) FROM valor WHERE estacion=?");
					stmt2.setInt(1, idEstacion);
					rs2 = stmt2.executeQuery();
					Date fecha = null;
					if (rs2.next())
					{
						fecha = rs2.getTimestamp(1);
					}
					System.out.println("Último registro: " + fecha);
					if (fecha == null || (new Date()).getTime() - fecha.getTime() >= 3600000 * HORAS_ENTRE_CONSULTA_EST)
					{
						System.out.println("Buscando nuevos registros.");
						Thread.sleep((long) (1000 + Math.random() * 4000)); // espera aleatoria de entre 1 y 5 segundos
						actualizar(conexion, idEstacion, fecha);
					}
					else
					{
						System.out.println("No buscando nuevos registros, anterior comprobación dentro de las últimas " + HORAS_ENTRE_CONSULTA_EST + " horas.");
					}
				}
				catch (IOException e)
				{
					System.out.println("Error al obtener datos de la estacion actual.");
					e.printStackTrace();
				}
				finally
				{
					if (rs2 != null)
					{
						rs2.close();
					}
				}
			}
			System.out.println("\nFinalizando Procesamiento: " + new Date());

		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		finally
		{
			try
			{
				if (rs != null)
				{
					rs.close();
				}
				if (stmt != null)
				{
					stmt.close();
				}
				if (conexion != null)
				{
					conexion.close();
				}
			}
			catch (SQLException e)
			{
				System.out.println("Error al cerrar la conexión");
				e.printStackTrace();
			}
		}
	}

	private static void actualizar(Connection conexion, int estacion, Date desde) throws ParseException, IOException, SQLException
	{
		String html = conexionGET(PAGINA_VALORES_ESTACION + estacion);

		int inicioTabla = html.indexOf(INICIO_TABLA) + INICIO_TABLA.length();
		int finTabla = html.indexOf(FIN_TABLA, inicioTabla);
		html = html.substring(inicioTabla, finTabla);

		String DATE_FORMAT = FORMATO_FECHA;
		SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);

		String[] filas = html.split(SEPARADOR_FILAS);
		System.out.print("Registros obtenidos " + (filas.length - 1));
		int nuevos = 0;
		int erroneos = 0;
		for (int i = 1; i < filas.length; i++)// descarto la primer fila que es el encabezado
		{
			try
			{
				String fila = filas[i];
				fila = fila.substring(INICIO_FILA.length(), fila.length() - FIN_FILA.length());
				String[] celdas = fila.split(SEPARADOR_CELDAS);

				Date fecha = sdf.parse(celdas[0].trim() + celdas[1].trim());
				String estado = celdas[2].trim();
				Float visibilidad;
				if (celdas[3].trim().startsWith(MENOR_A_100_METROS))
				{
					visibilidad = new Float(0);
				}
				else
				{
					if (celdas[3].trim().startsWith(MENOR_A))
					{
						celdas[3] = celdas[3].substring(MENOR_A.length(), celdas[3].length());
					}
					visibilidad = Float.parseFloat(celdas[3].trim().substring(0, celdas[3].trim().indexOf(" ")));
				}
				if (!celdas[3].trim().substring(celdas[3].trim().indexOf(" "), celdas[3].trim().length()).trim().equals("km"))
				{
					visibilidad /= 1000;
				}
				Float temperatura = Float.parseFloat(celdas[4].trim());
				Float sensacionTermica = null;
				try
				{
					sensacionTermica = Float.parseFloat(celdas[5].trim());
				}
				catch (NumberFormatException e1)
				{
					// no hay sensacion termina
				}
				Integer humedad = null;
				try
				{
					humedad = Integer.parseInt(celdas[6].trim());
				}
				catch (NumberFormatException e1)
				{
					// no hay humedad
				}

				String direccionViento = null;
				try
				{
					direccionViento = celdas[7].startsWith(NO_HAY_VIENTO) ? null : celdas[7].trim().substring(0, celdas[7].trim().indexOf(" "));
				}
				catch (Exception e)
				{
					// no hay viento
				}
				Integer intensidadViento = direccionViento == null ? 0 : Integer.parseInt(celdas[7].trim().substring(celdas[7].trim().indexOf(" "), celdas[7].trim().length()).trim());

				Float presion = null;
				try
				{
					presion = Float.parseFloat(celdas[8].trim());
				}
				catch (NumberFormatException e)
				{
					// no hay presion
				}
				if (desde == null || fecha.after(desde))
				{
					nuevos++;
					PreparedStatement stmt = conexion.prepareStatement("INSERT INTO valor (estacion, fecha, estado, visibilidad, temperatura, sensacionTermica, humedad, direccionViento, intensidadViento, "
							+ "presion) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

					stmt.setInt(1, estacion);
					stmt.setTimestamp(2, new Timestamp(fecha.getTime()));
					stmt.setString(3, estado);
					stmt.setFloat(4, visibilidad);
					stmt.setFloat(5, temperatura);
					if (sensacionTermica != null)
					{
						stmt.setFloat(6, sensacionTermica);
					}
					else
					{
						stmt.setNull(6, Types.FLOAT);
					}
					stmt.setInt(7, humedad);
					if (direccionViento != null)
					{
						stmt.setString(8, direccionViento);
					}
					else
					{
						stmt.setNull(8, Types.VARCHAR);
					}
					stmt.setInt(9, intensidadViento);
					if (presion != null)
					{
						stmt.setFloat(10, presion);
					}
					else
					{
						stmt.setNull(10, Types.FLOAT);
					}
					stmt.executeUpdate();
				}
			}
			catch (ParseException e)
			{
				System.out.println(new Date() + " - Error al parsear estación " + estacion + ".");
				e.printStackTrace();
				nuevos--;
				erroneos++;
			}
		}
		System.out.println(", nuevos " + nuevos + ", erroneos " + erroneos + ".");
	}
}
